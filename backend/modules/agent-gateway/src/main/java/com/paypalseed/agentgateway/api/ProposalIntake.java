package com.paypalseed.agentgateway.api;

import java.util.List;
import java.util.UUID;

/**
 * Where an agent's proposal enters the platform (04 §5). The body is untrusted: governance runs the owner
 * module's validator, the verdict feeds the kill switch, and hardship learns the outcome. Nothing here
 * changes a schedule.
 */
public interface ProposalIntake {

    String KIND_HARDSHIP_OUTREACH = "HARDSHIP_OUTREACH";

    /** {@code status}: QUEUED_FOR_MEMBER, SHADOWED or REJECTED. {@code result}: APPROVABLE or REJECTED. */
    record Receipt(UUID proposalId, String status, String result, int checks, List<FailedCheck> failed,
                   boolean templateFallback, boolean replay) {
        public Receipt {
            failed = List.copyOf(failed);
        }
    }

    record FailedCheck(String code, String detail) {
    }

    /** Idempotent on {@code idempotencyKey}: the same key and body return the first receipt. */
    Receipt submit(String token, String idempotencyKey, String proposalJson);

    /**
     * A run that ended without a proposal (kill switch, model unavailable, timeout, invalid output). The case
     * keeps its template text. In-process only: there is no HTTP route for it.
     */
    void reportFailure(UUID caseId, UUID runId, String errorCode);
}
