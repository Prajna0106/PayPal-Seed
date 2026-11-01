package com.paypalseed.agentgateway.infrastructure.web;

import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.ReasonExtractor;
import com.paypalseed.agentgateway.api.ReasonExtractor.Extraction;
import com.paypalseed.agentgateway.application.usecases.AgentGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The agent-facing control endpoints of agent-tools.yaml: the kill-switch read and the reason extractor. */
@RestController
class AgentControlController {

    record ExtractReasonRequest(@NotNull UUID caseId, @NotNull @Pattern(regexp = "en|es") String language,
                                @NotBlank @Size(max = 500) String text) {

        @Override
        public String toString() {
            return "ExtractReasonRequest[caseId=" + caseId + ", language=" + language + ", text=<redacted>]";
        }
    }

    record ExtractReasonResponse(String category, double confidence, String language, boolean usedFallback) {
    }

    private final KillSwitch killSwitch;
    private final AgentRuns runs;
    private final AgentGuard guard;
    private final ReasonExtractor extractor;

    AgentControlController(KillSwitch killSwitch, AgentRuns runs, AgentGuard guard, ReasonExtractor extractor) {
        this.killSwitch = killSwitch;
        this.runs = runs;
        this.guard = guard;
        this.extractor = extractor;
    }

    /** Still answers in TEMPLATES_ONLY: it is how an agent learns that it must stop. */
    @GetMapping("/agent/v1/kill-switch")
    KillSwitch.State killSwitch(AgentPrincipal agent) {
        agent.requireScope(AgentTokens.KILLSWITCH_READ);
        return killSwitch.state(runs.agentOf(agent.runId()));
    }

    @PostMapping("/agent/v1/extract-reason")
    ExtractReasonResponse extractReason(AgentPrincipal agent, @Valid @RequestBody ExtractReasonRequest body) {
        agent.requireScope(AgentTokens.EXTRACT_RUN).requireCase(body.caseId());
        guard.requireAgentsOn(agent);
        Extraction e = extractor.extract(body.caseId(), body.text(), body.language());
        return new ExtractReasonResponse(e.category(), e.confidencePct() / 100.0, body.language(), e.usedFallback());
    }
}
