package com.paypalseed.agentgateway.application.usecases;

import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.agentgateway.infrastructure.persistence.AgentRunRepository;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentRunService implements AgentRuns {

    private static final Set<String> MODES = Set.of("SECOND_OPINION", "BRIEF", "OUTREACH", "NARRATIVE", "EXTRACT");
    private static final Set<String> GROUNDING = Set.of("PASSED", "FAILED_FELL_BACK", "NOT_RUN");

    private final AgentRunRepository runs;
    private final KillSwitch killSwitch;

    public AgentRunService(AgentRunRepository runs, KillSwitch killSwitch) {
        this.runs = runs;
        this.killSwitch = killSwitch;
    }

    @Override
    public UUID start(String agent, UUID caseId, UUID loanId, String promptHash, String mode) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(promptHash, "promptHash");
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("unknown run mode " + mode);
        }
        UUID id = Ids.newId();
        runs.insert(id, agent, mode, killSwitch.mode(agent).name(), caseId, loanId, promptHash);
        return id;
    }

    @Override
    public void recordCall(UUID runId, ModelResponse response) {
        runs.addCall(runId, response.model(), response.tokensIn(), response.tokensOut(), response.costMicroUsd(),
                response.latencyMs());
    }

    @Override
    public int toolCallCount(UUID runId) {
        return runs.toolCallCount(runId);
    }

    @Override
    public int recordToolCall(UUID runId, String tool, long latencyMs, String argsHash) {
        return runs.addToolCall(runId, tool, Math.max(0, latencyMs), argsHash);
    }

    /** Finishing twice is a no-op: the first verdict stands. */
    @Override
    public void finish(UUID runId, String groundingResult, String verdict, String errorCode) {
        if (groundingResult != null && !GROUNDING.contains(groundingResult)) {
            throw new IllegalArgumentException("unknown grounding result " + groundingResult);
        }
        runs.finish(runId, groundingResult, verdict, errorCode);
    }

    @Override
    public String agentOf(UUID runId) {
        return runs.agentOf(runId).orElseThrow(() -> new DomainException(CommonErrors.NOT_FOUND, "No such agent run"));
    }

    @Override
    public int runsForCaseSince(UUID caseId, Instant since) {
        return runs.runsForCaseSince(caseId, since);
    }
}
