package com.paypalseed.guardian.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.api.AgentTools;
import com.paypalseed.agentgateway.api.AgentTools.ToolResult;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.ModelGateway;
import com.paypalseed.agentgateway.api.ModelUnavailableException;
import com.paypalseed.agentgateway.api.ProposalIntake;
import com.paypalseed.agentgateway.api.ProposalIntake.Receipt;
import com.paypalseed.agentgateway.api.llm.ChatMessage;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.agentgateway.api.llm.ToolCall;
import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import com.paypalseed.engines.rules.hardship.RiskLevel;
import com.paypalseed.engines.rules.hardship.ScoreResult;
import com.paypalseed.engines.rules.hardship.SecondOpinion;
import com.paypalseed.engines.rules.hardship.Signal;
import com.paypalseed.guardian.application.usecases.ProposalAssembler.Assessment;
import com.paypalseed.guardian.domain.model.PromptLibrary;
import com.paypalseed.guardian.domain.model.RunBudget;
import com.paypalseed.guardian.domain.model.RunFailure;
import com.paypalseed.guardian.domain.model.RunFailure.Code;
import com.paypalseed.guardian.domain.model.Schemas;
import com.paypalseed.guardian.domain.model.UntrustedData;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * One Guardian run for one case (04 §3).
 *
 * <ol>
 *   <li>The code fetches the signals, the schedule and the offered options through the read tools.</li>
 *   <li>The model may call more read tools, within the run's six calls and 45 seconds.</li>
 *   <li>For a borderline score the model gives a second opinion; the engine's escalate-only rule decides
 *       what, if anything, it changes.</li>
 *   <li>The model writes the officer brief and the member message as strict JSON, citing its figures.</li>
 *   <li>The code assembles the proposal and files it. Validation, consent and approval happen elsewhere.</li>
 * </ol>
 *
 * Any failure ends the run with a failure code and the case keeps its template text. The token is revoked
 * when the run ends, whatever happened.
 */
@Service
public class GuardianRunner {

    public static final String AGENT = "guardian";
    public static final String AGENT_VERSION = "guardian@1.0.0";
    public static final String MODE_SECOND_OPINION = "SECOND_OPINION_THEN_NARRATIVE";

    private static final Logger log = LoggerFactory.getLogger(GuardianRunner.class);
    private static final Set<String> SCOPES = Set.of(AgentTokens.LOAN_READ, AgentTokens.SCHEDULE_READ,
            AgentTokens.SIGNALS_READ, AgentTokens.INCOME_READ, AgentTokens.PREVIEW_RUN, AgentTokens.PROPOSAL_SUBMIT);
    private static final List<String> PREFETCH = List.of(AgentTools.GET_HARDSHIP_SIGNALS, AgentTools.GET_REPAYMENT_SCHEDULE,
            AgentTools.LIST_OFFERED_OPTIONS);
    private static final int MAX_GATHER_ROUNDS = 3;
    private static final int RUNS_PER_CASE_PER_DAY = 3;
    private static final int GATHER_TOKENS = 400;
    private static final int OPINION_TOKENS = 300;
    private static final int NARRATIVE_TOKENS = 1500;
    private static final String AGENT_DISABLED_ERROR = "agent-disabled";

    record ToolOutput(String tool, String json) {
    }

    private final ModelGateway model;
    private final AgentTools tools;
    private final AgentTokens tokens;
    private final AgentRuns runs;
    private final KillSwitch killSwitch;
    private final ProposalIntake intake;
    private final ObjectMapper json;
    private final PromptLibrary prompts;
    private final Supplier<RunBudget> budgets;
    private final Clock clock;

    @Autowired
    public GuardianRunner(ModelGateway model, AgentTools tools, AgentTokens tokens, AgentRuns runs, KillSwitch killSwitch,
                          ProposalIntake intake, ObjectMapper json) {
        this(model, tools, tokens, runs, killSwitch, intake, json, PromptLibrary.load(), RunBudget::start, Clock.systemUTC());
    }

    GuardianRunner(ModelGateway model, AgentTools tools, AgentTokens tokens, AgentRuns runs, KillSwitch killSwitch,
                   ProposalIntake intake, ObjectMapper json, PromptLibrary prompts, Supplier<RunBudget> budgets, Clock clock) {
        this.model = model;
        this.tools = tools;
        this.tokens = tokens;
        this.runs = runs;
        this.killSwitch = killSwitch;
        this.intake = intake;
        this.json = json;
        this.prompts = prompts;
        this.budgets = budgets;
        this.clock = clock;
    }

    public void run(UUID caseId, UUID loanId, String requestedMode) {
        if (runs.runsForCaseSince(caseId, clock.instant().minus(Duration.ofDays(1))) >= RUNS_PER_CASE_PER_DAY) {
            log.warn("Guardian skipped case {}: {} runs in the last day", caseId, RUNS_PER_CASE_PER_DAY);
            return;
        }
        boolean secondOpinion = MODE_SECOND_OPINION.equals(requestedMode);
        UUID runId = runs.start(AGENT, caseId, loanId, prompts.hash(), secondOpinion ? "SECOND_OPINION" : "NARRATIVE");
        if (killSwitch.mode(AGENT) == KillSwitch.Mode.TEMPLATES_ONLY) {
            intake.reportFailure(caseId, runId, Code.AGENT_DISABLED.name());
            return;
        }
        String token = tokens.issue(runId, loanId, caseId, SCOPES, AgentTokens.MAX_TTL).token();
        try {
            RunBudget budget = budgets.get();
            List<ToolOutput> outputs = gather(runId, caseId, loanId, token, budget);
            Assessment assessment = assess(outputs, secondOpinion ? secondOpinion(runId, caseId, loanId, outputs, budget) : null);
            JsonNode narrative = narrative(runId, caseId, loanId, outputs, budget);
            ObjectNode proposal = ProposalAssembler.assemble(json, runId, caseId, loanId, AGENT_VERSION, prompts.hash(),
                    assessment, last(outputs, AgentTools.LIST_OFFERED_OPTIONS), narrative);
            Receipt receipt = intake.submit(token, runId.toString(), proposal.toString());
            log.info("Guardian run {} for case {}: {} ({} failed checks)", runId, caseId, receipt.status(), receipt.failed().size());
        } catch (RunFailure f) {
            log.info("Guardian run {} for case {} ended without a proposal: {}", runId, caseId, f.code());
            intake.reportFailure(caseId, runId, f.code().name());
        } catch (ModelUnavailableException e) {
            log.info("Guardian run {} for case {}: model unavailable ({})", runId, caseId, e.reason());
            intake.reportFailure(caseId, runId, failureFor(e.reason()).name());
        } catch (DomainException e) {
            log.warn("Guardian run {} for case {}: proposal refused ({})", runId, caseId, e.errorCode().code());
            intake.reportFailure(caseId, runId, Code.PROPOSAL_ERROR.name());
        } finally {
            tokens.revoke(runId);
        }
    }

    /** The code's own reads first, then the model's: the model can ask for more, never for less. */
    List<ToolOutput> gather(UUID runId, UUID caseId, UUID loanId, String token, RunBudget budget) {
        List<ToolOutput> outputs = new ArrayList<>();
        String args = json.createObjectNode().put("loanId", loanId.toString()).toString();
        for (String tool : PREFETCH) {
            budget.spendToolCall();
            ToolResult result = tools.call(token, tool, args);
            if (!result.ok()) {
                throw AGENT_DISABLED_ERROR.equals(result.errorCode())
                        ? new RunFailure(Code.AGENT_DISABLED, "Agents were switched off during the run")
                        : new RunFailure(Code.TOOL_ERROR, tool + " failed: " + result.errorCode());
            }
            outputs.add(new ToolOutput(tool, result.json()));
        }

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system(prompts.gather(budget.remainingToolCalls())));
        conversation.add(ChatMessage.user(context(caseId, loanId, outputs)));
        for (int round = 0; round < MAX_GATHER_ROUNDS && budget.toolCallAvailable(); round++) {
            ModelResponse response = complete(new ModelRequest(AGENT, runId, "NARRATIVE", conversation, tools.catalogue(),
                    null, null, GATHER_TOKENS, budget.nextCallTimeout()), runId);
            if (!response.hasToolCalls()) {
                break;
            }
            conversation.add(ChatMessage.assistant(response.content(), response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                conversation.add(ChatMessage.tool(call.id(), UntrustedData.wrap(call.name(), answer(call, token, budget, outputs))));
            }
        }
        return outputs;
    }

    private String answer(ToolCall call, String token, RunBudget budget, List<ToolOutput> outputs) {
        if (!budget.toolCallAvailable()) {
            return error("tool-call-limit", "No tool calls left in this run");
        }
        if (!isJsonObject(call.argumentsJson())) {
            return error("invalid-arguments", "The arguments must be a JSON object; the call was not run");
        }
        budget.spendToolCall();
        ToolResult result = tools.call(token, call.name(), call.argumentsJson());
        if (AGENT_DISABLED_ERROR.equals(result.errorCode())) {
            throw new RunFailure(Code.AGENT_DISABLED, "Agents were switched off during the run");
        }
        if (result.ok()) {
            outputs.add(new ToolOutput(call.name(), result.json()));
        }
        return result.json();
    }

    /** The model's view, which the engine's escalate-only rule may adopt. Invalid output just means no change. */
    SecondOpinion.Opinion secondOpinion(UUID runId, UUID caseId, UUID loanId, List<ToolOutput> outputs, RunBudget budget) {
        ModelResponse response = complete(new ModelRequest(AGENT, runId, "SECOND_OPINION",
                List.of(ChatMessage.system(prompts.secondOpinion()), ChatMessage.user(context(caseId, loanId, outputs))),
                List.of(), Schemas.SECOND_OPINION_NAME, Schemas.SECOND_OPINION, OPINION_TOKENS, budget.nextCallTimeout()), runId);
        JsonNode node = parseObject(response.content());
        if (node == null || !node.path("proposedLevel").isTextual() || !node.path("citedSignals").isArray()) {
            log.info("Guardian run {}: second opinion was not valid JSON for the schema; ignored", runId);
            return null;
        }
        try {
            RiskLevel level = RiskLevel.valueOf(node.path("proposedLevel").asText());
            List<String> cited = new ArrayList<>();
            node.path("citedSignals").forEach(c -> cited.add(c.asText()));
            return new SecondOpinion.Opinion(level, cited, "MODEL");
        } catch (IllegalArgumentException e) {
            log.info("Guardian run {}: second opinion named an unknown level; ignored", runId);
            return null;
        }
    }

    /** The level the proposal carries: the code's, or one step higher only when the engine accepts the opinion. */
    Assessment assess(List<ToolOutput> outputs, SecondOpinion.Opinion opinion) {
        JsonNode signals = last(outputs, AgentTools.GET_HARDSHIP_SIGNALS);
        RiskLevel computed = RiskLevel.valueOf(signals.path("computedLevel").asText(RiskLevel.NONE.name()));
        List<Signal> fired = new ArrayList<>();
        for (JsonNode s : signals.path("signals")) {
            Map<String, Long> evidence = new LinkedHashMap<>();
            s.path("evidence").fields().forEachRemaining(e -> {
                if (e.getValue().isIntegralNumber()) {
                    evidence.put(e.getKey(), e.getValue().asLong());
                }
            });
            fired.add(new Signal(s.path("code").asText(), s.path("points").asInt(), evidence));
        }
        List<String> firedCodes = fired.stream().filter(s -> s.points() > 0).map(Signal::code).toList();
        if (opinion == null) {
            return new Assessment(computed, computed, false, firedCodes);
        }
        ScoreResult result = new ScoreResult(signals.path("points").asInt(), computed, fired);
        if (SecondOpinion.evaluate(result, opinion, true, HardshipPolicy.v1()) instanceof SecondOpinion.Accepted accepted) {
            return new Assessment(computed, accepted.newLevel(), true, List.copyOf(opinion.citedSignals()));
        }
        return new Assessment(computed, computed, false, firedCodes);
    }

    JsonNode narrative(UUID runId, UUID caseId, UUID loanId, List<ToolOutput> outputs, RunBudget budget) {
        ModelResponse response = complete(new ModelRequest(AGENT, runId, "NARRATIVE",
                List.of(ChatMessage.system(prompts.narrative()), ChatMessage.user(context(caseId, loanId, outputs))),
                List.of(), Schemas.NARRATIVE_NAME, Schemas.NARRATIVE, NARRATIVE_TOKENS, budget.nextCallTimeout()), runId);
        JsonNode node = parseObject(response.content());
        if (node == null || !node.path("officerBrief").isTextual() || !node.path("memberMessage").path("en").isTextual()
                || !node.path("memberMessage").path("es").isTextual() || !node.path("figures").isArray()) {
            throw new RunFailure(Code.SCHEMA_INVALID, "The narrative did not match its schema");
        }
        for (JsonNode f : node.path("figures")) {
            if (!f.isObject() || !f.path("unit").isTextual() || !f.path("tool").isTextual() || !f.path("field").isTextual()) {
                throw new RunFailure(Code.SCHEMA_INVALID, "A figure did not match its schema");
            }
        }
        return node;
    }

    private ModelResponse complete(ModelRequest request, UUID runId) {
        ModelResponse response = model.complete(request);
        runs.recordCall(runId, response);
        return response;
    }

    private static String context(UUID caseId, UUID loanId, List<ToolOutput> outputs) {
        StringBuilder text = new StringBuilder()
                .append("Case ").append(caseId).append(" on loan ").append(loanId).append(".\n")
                .append("Use loanId \"").append(loanId).append("\" in every tool call.\n");
        outputs.forEach(o -> text.append(UntrustedData.wrap(o.tool(), o.json())).append('\n'));
        return text.toString();
    }

    private JsonNode last(List<ToolOutput> outputs, String tool) {
        for (int i = outputs.size() - 1; i >= 0; i--) {
            if (outputs.get(i).tool().equals(tool)) {
                JsonNode node = parseObject(outputs.get(i).json());
                if (node != null) {
                    return node;
                }
            }
        }
        throw new RunFailure(Code.TOOL_ERROR, "No usable " + tool + " result");
    }

    private JsonNode parseObject(String text) {
        if (text == null) {
            return null;
        }
        try {
            JsonNode node = json.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private boolean isJsonObject(String text) {
        return parseObject(text) != null;
    }

    private String error(String code, String detail) {
        return json.createObjectNode().put("error", code).put("detail", detail).toString();
    }

    private static Code failureFor(ModelUnavailableException.Reason reason) {
        return switch (reason) {
            case DISABLED -> Code.AGENT_DISABLED;
            case BUDGET_EXHAUSTED -> Code.BUDGET;
            default -> Code.MODEL_UNAVAILABLE;
        };
    }
}
