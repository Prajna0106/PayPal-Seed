package com.paypalseed.agentgateway.application.usecases;

import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.domain.model.AgentErrors;
import com.paypalseed.platform.kernel.DomainException;
import org.springframework.stereotype.Component;

/** Every agent endpoint except the kill-switch read answers 503 {@code agent-disabled} in TEMPLATES_ONLY. */
@Component
public class AgentGuard {

    private final KillSwitch killSwitch;
    private final AgentRuns runs;

    public AgentGuard(KillSwitch killSwitch, AgentRuns runs) {
        this.killSwitch = killSwitch;
        this.runs = runs;
    }

    public AgentPrincipal requireAgentsOn(AgentPrincipal principal) {
        if (killSwitch.mode(runs.agentOf(principal.runId())) == KillSwitch.Mode.TEMPLATES_ONLY) {
            throw new DomainException(AgentErrors.AGENT_DISABLED, "Agents are in TEMPLATES_ONLY mode");
        }
        return principal;
    }
}
