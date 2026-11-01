package com.paypalseed.agentgateway.api;

import com.paypalseed.agentgateway.api.llm.ModelResponse;
import java.time.Instant;
import java.util.UUID;

/**
 * The trace of every agent run (04 §12). Rows hold hashes, counts, cost and verdicts, never prompt or
 * member text. A finished run is append-only.
 */
public interface AgentRuns {

    /** Starts a run and records the kill-switch mode in force; {@code mode} is SECOND_OPINION, BRIEF, OUTREACH, NARRATIVE or EXTRACT. */
    UUID start(String agent, UUID caseId, UUID loanId, String promptHash, String mode);

    void recordCall(UUID runId, ModelResponse response);

    /** Tool calls the run has made so far. */
    int toolCallCount(UUID runId);

    /** Records a tool call and returns how many the run has made, so the caller can enforce the cap. */
    int recordToolCall(UUID runId, String tool, long latencyMs, String argsHash);

    /** {@code groundingResult} is PASSED, FAILED_FELL_BACK or NOT_RUN. */
    void finish(UUID runId, String groundingResult, String verdict, String errorCode);

    /** The agent a run belongs to, for kill-switch checks on tool calls. */
    String agentOf(UUID runId);

    /** Runs started for a case since {@code since}: feeds the runs-per-case-per-day limit. */
    int runsForCaseSince(UUID caseId, Instant since);
}
