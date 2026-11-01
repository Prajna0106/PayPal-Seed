package com.paypalseed.governance.api;

import com.paypalseed.governance.api.dto.GovernanceDtos.ApprovalView;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.governance.api.dto.GovernanceDtos.ChainVerification;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decide;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionStat;
import com.paypalseed.governance.api.dto.GovernanceDtos.OpenApproval;
import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.SubmitProposal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Proposal, verdict, approval and the audit chain (ADR 0003, ADR 0009). Generic: owner modules register a
 * {@link ProposalValidator} per proposal kind and keep the meaning of the subject to themselves.
 */
public interface GovernanceFacade {

    /**
     * Stores the proposal and runs the validator for its kind. Same idempotency key and payload: the stored
     * result. Same key, other payload: {@code idempotency-key-reused}.
     */
    ProposalResult submitProposal(SubmitProposal command);

    /**
     * Opens the decision. {@code AUTO_POLICY} is approved by the policy at once. Opening again for the same
     * subject and snapshot returns the open request.
     */
    UUID openApprovalRequest(OpenApproval command);

    /**
     * Records the maker's or the checker's decision. A snapshot mismatch voids the request and throws
     * {@link ApprovalStaleException}; the void is kept even though the call fails.
     */
    DecisionResult decide(Decide command);

    /** Withdraws an open request (stale preview, withdrawn choice). Idempotent. */
    void voidRequest(UUID requestId, String reason);

    Optional<ApprovalView> request(UUID requestId);

    Optional<ApprovalView> openRequestFor(UUID subjectId);

    /** The officer's latest decisions, newest first, for the rubber-stamp detector. */
    List<DecisionStat> recentDecisions(UUID staffId, int limit);

    /** Appends to the hash-chained audit log; returns the entry's sequence number. */
    long audit(AuditCommand command);

    ChainVerification verifyChain();
}
