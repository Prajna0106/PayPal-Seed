package com.paypalseed.agentgateway.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.KillSwitch.Mode;
import com.paypalseed.agentgateway.api.ModelGateway;
import com.paypalseed.agentgateway.api.ModelUnavailableException;
import com.paypalseed.agentgateway.api.llm.ChatMessage;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.deskquery.api.DeskAnalystNarrator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Desk Analyst Agent runner (`desk-analyst-agent`, ADR 0009 & ADR 0010).
 *
 * <p>Synthesizes deterministic portfolio health metrics, reserve coverage, and loan officer
 * review queues into an executive morning briefing, strictly checked against source numbers
 * and policy guardrails.
 */
@Service
public class DeskAnalystAgentRunner implements DeskAnalystNarrator {

    public static final String AGENT = "desk-analyst-agent";
    public static final String SCHEMA_NAME = "desk_analyst_briefing";
    public static final String SCHEMA = """
        {"type":"object","additionalProperties":false,\
        "required":["executiveSummary","queuePriorities","riskObservations","recommendedStaffingFocus"],\
        "properties":{"executiveSummary":{"type":"string"},\
        "queuePriorities":{"type":"array","items":{"type":"string"}},\
        "riskObservations":{"type":"array","items":{"type":"string"}},\
        "recommendedStaffingFocus":{"type":"string"}}}""";

    private static final Logger log = LoggerFactory.getLogger(DeskAnalystAgentRunner.class);
    private static final int MAX_OUTPUT_TOKENS = 600;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(12);
    private static final Pattern DOLLAR_AMOUNT = NarrationGuard.DOLLAR_AMOUNT;

    private static final String SYSTEM_PROMPT = loadPrompt("/desk-analyst/prompts/v1/briefing.md");
    public static final String PROMPT_HASH = "sha256:" + sha256Hex(SYSTEM_PROMPT + "\u0000" + SCHEMA).substring(0, 16);

    private final ModelGateway models;
    private final AgentRuns runs;
    private final KillSwitch killSwitch;
    private final ObjectMapper json;

    public DeskAnalystAgentRunner(ModelGateway models, AgentRuns runs, KillSwitch killSwitch, ObjectMapper json) {
        this.models = models;
        this.runs = runs;
        this.killSwitch = killSwitch;
        this.json = json;
    }

    @Override
    public DeskBriefingResult brief(DeskBriefingInput input) {
        DeskBriefingResult fallback = new DeskBriefingResult(
            input.templateExecutiveSummary(),
            input.templateQueuePriorities(),
            input.templateRiskObservations(),
            input.templateStaffingFocus(),
            false
        );
        Mode mode = killSwitch.mode(AGENT);
        UUID runId = runs.start(AGENT, null, null, PROMPT_HASH, "NARRATIVE");
        if (mode == Mode.TEMPLATES_ONLY) {
            runs.finish(runId, "NOT_RUN", "TEMPLATES_ONLY", "AGENT_DISABLED");
            return fallback;
        }

        try {
            ModelResponse response = models.complete(new ModelRequest(
                AGENT,
                runId,
                "NARRATIVE",
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(buildUserContext(input))),
                List.of(),
                SCHEMA_NAME,
                SCHEMA,
                MAX_OUTPUT_TOKENS,
                CALL_TIMEOUT
            ));
            runs.recordCall(runId, response);

            DeskBriefingResult parsed = parseAndValidate(response.content(), input);
            if (parsed == null) {
                killSwitch.recordGroundingFailure(AGENT);
                runs.finish(runId, "FAILED_FELL_BACK", "TEMPLATE_FALLBACK", "UNGROUNDED_OR_INVALID");
                return fallback;
            }

            if (mode == Mode.SHADOW) {
                runs.finish(runId, "PASSED", "SHADOW_TEMPLATE", null);
                return fallback;
            }

            runs.finish(runId, "PASSED", "MODEL", null);
            return parsed;
        } catch (ModelUnavailableException e) {
            log.info("Desk analyst runner run {} fell back to template ({})", runId, e.reason());
            runs.finish(runId, "NOT_RUN", "TEMPLATE_FALLBACK", e.reason().name());
            return fallback;
        }
    }

    DeskBriefingResult parseAndValidate(String content, DeskBriefingInput input) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(content);
            if (!root.isObject()) {
                return null;
            }
            JsonNode execNode = root.get("executiveSummary");
            JsonNode prioritiesNode = root.get("queuePriorities");
            JsonNode obsNode = root.get("riskObservations");
            JsonNode staffingNode = root.get("recommendedStaffingFocus");
            if (execNode == null || !execNode.isTextual() ||
                prioritiesNode == null || !prioritiesNode.isArray() ||
                obsNode == null || !obsNode.isArray() ||
                staffingNode == null || !staffingNode.isTextual()) {
                return null;
            }

            String execSummary = execNode.asText().strip();
            String staffingFocus = staffingNode.asText().strip();
            if (execSummary.isBlank() || staffingFocus.isBlank()) {
                return null;
            }

            if (execSummary.length() > 400 || staffingFocus.length() > 250) {
                return null;
            }

            List<String> priorities = new ArrayList<>();
            for (JsonNode item : prioritiesNode) {
                if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 250) {
                    return null;
                }
                priorities.add(item.asText().strip());
            }

            List<String> observations = new ArrayList<>();
            for (JsonNode item : obsNode) {
                if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 250) {
                    return null;
                }
                observations.add(item.asText().strip());
            }

            if (priorities.isEmpty() || observations.isEmpty()) {
                return null;
            }

            // Guardrails
            if (NarrationGuard.violates(execSummary) || NarrationGuard.violates(staffingFocus)) {
                return null;
            }
            for (String p : priorities) {
                if (NarrationGuard.violates(p)) {
                    return null;
                }
            }
            for (String o : observations) {
                if (NarrationGuard.violates(o)) {
                    return null;
                }
            }

            // Grounding on dollar amounts
            Set<String> allowedFigures = buildAllowedFigures(input);
            if (!allDollarFiguresAllowed(execSummary, allowedFigures) ||
                !allDollarFiguresAllowed(staffingFocus, allowedFigures)) {
                return null;
            }
            for (String p : priorities) {
                if (!allDollarFiguresAllowed(p, allowedFigures)) {
                    return null;
                }
            }
            for (String o : observations) {
                if (!allDollarFiguresAllowed(o, allowedFigures)) {
                    return null;
                }
            }

            return new DeskBriefingResult(execSummary, List.copyOf(priorities), List.copyOf(observations), staffingFocus, true);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static String buildUserContext(DeskBriefingInput input) {
        return """
            <computed_portfolio_metrics>
            business_date: %s
            active_loans: %d
            total_outstanding: %s
            healthy_loans: %d
            at_risk_loans: %d
            cases_pending_review: %d
            oldest_case_hours: %d
            sla_breaches: %d
            reserve_balance: %s
            reserve_coverage_ratio: %.2fx
            </computed_portfolio_metrics>
            """.formatted(
                input.businessDate(),
                input.activeLoansCount(),
                formatDollar(input.totalOutstandingCents()),
                input.healthyLoansCount(),
                input.atRiskLoansCount(),
                input.casesPendingReviewCount(),
                input.oldestCaseHours(),
                input.slaBreachesCount(),
                formatDollar(input.reserveBalanceCents()),
                input.reserveCoverageRatio()
            );
    }

    private static Set<String> buildAllowedFigures(DeskBriefingInput input) {
        Set<String> figures = new LinkedHashSet<>();
        figures.add(formatDollar(input.totalOutstandingCents()));
        figures.add(formatDollar(input.reserveBalanceCents()));
        figures.add("$0");
        return figures;
    }

    private static boolean allDollarFiguresAllowed(String text, Set<String> allowed) {
        Matcher matcher = DOLLAR_AMOUNT.matcher(text);
        while (matcher.find()) {
            String found = matcher.group();
            if (!allowed.contains(found) && !allowed.contains(found.replace(".00", ""))) {
                return false;
            }
        }
        return true;
    }

    public static String formatDollar(long cents) {
        long dollars = cents / 100;
        long rem = Math.abs(cents % 100);
        if (rem == 0) {
            return String.format(Locale.US, "$%,d", dollars);
        }
        return String.format(Locale.US, "$%,d.%02d", dollars, rem);
    }

    private static String loadPrompt(String path) {
        try (InputStream in = DeskAnalystAgentRunner.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
