package com.paypalseed.agentgateway.api;

import com.paypalseed.agentgateway.api.llm.ToolSpec;
import java.util.List;

/**
 * The read tools of {@code contracts/openapi/agent-tools.yaml}, for agents that run inside the process.
 * External runtimes reach the same tools over HTTP. Both paths enforce the run token's loan, case and
 * scopes, the kill switch and the per-run cap of six calls.
 */
public interface AgentTools {

    String GET_LOAN_SUMMARY = "get_loan_summary";
    String GET_REPAYMENT_SCHEDULE = "get_repayment_schedule";
    String GET_LOAN_AGING = "get_loan_aging";
    String GET_HARDSHIP_SIGNALS = "get_hardship_signals";
    String GET_INCOME_SUMMARY = "get_income_summary";
    String LIST_OFFERED_OPTIONS = "list_offered_options";
    String PREVIEW_OPTION = "preview_option";
    String LIST_PRIOR_CASES = "list_prior_cases";

    int MAX_CALLS_PER_RUN = 6;

    /**
     * Outcome of one call. A refused call is not an exception: {@code json} then carries
     * {@code {"error": code, "detail": text}} so a model can read why, and {@code errorCode} is set.
     */
    record ToolResult(String tool, boolean ok, String json, String errorCode) {
    }

    /** The read tools as model function specifications. */
    List<ToolSpec> catalogue();

    ToolResult call(String token, String toolName, String argumentsJson);
}
