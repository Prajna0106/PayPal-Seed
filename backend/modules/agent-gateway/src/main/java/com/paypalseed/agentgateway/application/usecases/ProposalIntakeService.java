package com.paypalseed.agentgateway.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.ProposalIntake;
import com.paypalseed.agentgateway.domain.model.AgentErrors;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.Check;
import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.SubmitProposal;
import com.paypalseed.hardship.api.HardshipFacade;
import com.paypalseed.hardship.api.dto.HardshipDtos.AgentOutcome;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a Guardian proposal in. Governance stores it and runs hardship's validator (V01 to V12); this class
 * adds the token binding, feeds the kill switch's auto-trip rules, finishes the run trace and tells
 * hardship the outcome. A replay returns the first receipt and changes nothing.
 */
@Service
public class ProposalIntakeService implements ProposalIntake {

    static final String V02_TOKEN_BINDING = "V02_TOKEN_BINDING";
    private static final String V06_PREFIX = "V06";

    private final AgentTokens tokens;
    private final AgentRuns runs;
    private final AgentGuard guard;
    private final KillSwitch killSwitch;
    private final GovernanceFacade governance;
    private final HardshipFacade hardship;
    private final ObjectMapper json;

    public ProposalIntakeService(AgentTokens tokens, AgentRuns runs, AgentGuard guard, KillSwitch killSwitch,
                                 GovernanceFacade governance, HardshipFacade hardship, ObjectMapper json) {
        this.tokens = tokens;
        this.runs = runs;
        this.guard = guard;
        this.killSwitch = killSwitch;
        this.governance = governance;
        this.hardship = hardship;
        this.json = json;
    }

    @Override
    @Transactional
    public Receipt submit(String token, String idempotencyKey, String proposalJson) {
        AgentPrincipal principal = tokens.verify(token)
                .orElseThrow(() -> new DomainException(CommonErrors.UNAUTHENTICATED, "The agent token is unknown, expired or revoked"));
        return submit(principal, idempotencyKey, proposalJson);
    }

    @Transactional
    public Receipt submit(AgentPrincipal principal, String idempotencyKey, String proposalJson) {
        principal.requireScope(AgentTokens.PROPOSAL_SUBMIT);
        guard.requireAgentsOn(principal);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(AgentErrors.IDEMPOTENCY_KEY_REQUIRED, "Send the run id as the Idempotency-Key");
        }
        JsonNode body = parse(proposalJson);
        boolean bound = boundToToken(body, principal);

        ProposalResult result = governance.submitProposal(new SubmitProposal(KIND_HARDSHIP_OUTREACH, principal.caseId(),
                proposalJson, "run:" + principal.runId(), idempotencyKey));
        List<FailedCheck> failed = new ArrayList<>();
        for (Check check : result.checks()) {
            if (!check.passed()) {
                failed.add(new FailedCheck(check.code(), check.detail()));
            }
        }
        if (!bound && failed.stream().noneMatch(f -> f.code().equals(V02_TOKEN_BINDING))) {
            failed.add(new FailedCheck(V02_TOKEN_BINDING, "runId, caseId or loanId differ from the run token"));
        }
        boolean approvable = result.approvable() && bound;
        String agent = runs.agentOf(principal.runId());
        boolean shadow = killSwitch.mode(agent) == KillSwitch.Mode.SHADOW;
        boolean ungrounded = failed.stream().anyMatch(f -> f.code().startsWith(V06_PREFIX));

        if (!result.replay()) {
            killSwitch.recordValidatorOutcome(agent, !approvable);
            if (ungrounded) {
                killSwitch.recordGroundingFailure(agent);
            }
            hardship.recordAgentOutcome(outcome(principal, result.proposalId(), approvable, shadow, body,
                    failed.stream().map(FailedCheck::code).toList()));
            runs.finish(principal.runId(), ungrounded ? "FAILED_FELL_BACK" : "PASSED", approvable ? "APPROVABLE" : "REJECTED", null);
        }
        String status = !approvable ? "REJECTED" : shadow ? "SHADOWED" : "QUEUED_FOR_MEMBER";
        return new Receipt(result.proposalId(), status, approvable ? "APPROVABLE" : "REJECTED", result.checks().size(),
                failed, !approvable || shadow, result.replay());
    }

    @Override
    @Transactional
    public void reportFailure(UUID caseId, UUID runId, String errorCode) {
        hardship.recordAgentOutcome(new AgentOutcome(caseId, runId, null, false, false, null, List.of(), Map.of(), null,
                List.of(), errorCode));
        runs.finish(runId, "NOT_RUN", "FAILED", errorCode);
    }

    private JsonNode parse(String proposalJson) {
        try {
            JsonNode node = json.readTree(proposalJson);
            return node == null || !node.isObject() ? json.createObjectNode() : node;
        } catch (JsonProcessingException e) {
            return json.createObjectNode();
        }
    }

    private static boolean boundToToken(JsonNode body, AgentPrincipal principal) {
        return principal.runId().toString().equals(body.path("runId").asText(null))
                && principal.caseId().toString().equals(body.path("caseId").asText(null))
                && principal.loanId().toString().equals(body.path("loanId").asText(null));
    }

    /** Only these fields of the untrusted body travel on, and hardship stores them only for an approvable verdict. */
    private static AgentOutcome outcome(AgentPrincipal principal, UUID proposalId, boolean approvable, boolean shadow,
                                        JsonNode body, List<String> failedChecks) {
        JsonNode assessment = body.path("assessment");
        List<String> cited = new ArrayList<>();
        assessment.path("citedSignals").forEach(n -> cited.add(n.asText()));
        Map<String, String> messages = new LinkedHashMap<>();
        body.path("narrative").path("memberMessage").fields().forEachRemaining(e -> {
            if (e.getValue().isTextual()) {
                messages.put(e.getKey(), e.getValue().asText());
            }
        });
        String brief = body.path("narrative").path("officerBrief").asText(null);
        return new AgentOutcome(principal.caseId(), principal.runId(), proposalId, approvable, shadow,
                assessment.path("proposedLevel").asText(null), cited, messages, brief, failedChecks, null);
    }
}
