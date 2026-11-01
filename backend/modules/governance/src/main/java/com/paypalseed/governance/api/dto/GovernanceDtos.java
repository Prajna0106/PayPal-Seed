package com.paypalseed.governance.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class GovernanceDtos {

    private GovernanceDtos() {
    }

    public enum Tier {
        /** Within policy: approved by the policy itself when the request opens (option A). */
        AUTO_POLICY,
        MAKER_CHECKER
    }

    public enum Decision {
        APPROVE,
        REJECT
    }

    /** {@code payloadJson} is untrusted; only the validator registered for {@code kind} judges it. */
    public record SubmitProposal(String kind, UUID subjectId, String payloadJson, String submittedBy, String idempotencyKey) {
        public SubmitProposal {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(payloadJson, "payloadJson");
            Objects.requireNonNull(submittedBy, "submittedBy");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        }
    }

    /** What a validator sees: the stored proposal, never the raw request. */
    public record ProposalEnvelope(UUID proposalId, String kind, UUID subjectId, String payloadJson, String submittedBy) {
    }

    public record Check(String code, boolean passed, String detail) {
    }

    public record Verdict(boolean approvable, List<Check> checks) {
        public Verdict {
            checks = List.copyOf(checks);
        }

        public static Verdict of(List<Check> checks) {
            return new Verdict(checks.stream().allMatch(Check::passed), checks);
        }
    }

    /** {@code replay} is true when the idempotency key was seen before with the same payload. */
    public record ProposalResult(UUID proposalId, boolean approvable, List<Check> checks, boolean replay) {
        public ProposalResult {
            checks = List.copyOf(checks);
        }
    }

    public record OpenApproval(UUID subjectId, UUID subjectMemberId, Tier tier, UUID offerId, String snapshotHash,
                               Instant slaDueAt, boolean checkerRequired) {
        public OpenApproval {
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(snapshotHash, "snapshotHash");
        }
    }

    /**
     * One human decision. The first decision on a request is the maker's, the second the checker's.
     * {@code currentSnapshotHash} is recomputed by the caller from a fresh preview; a mismatch voids the request.
     */
    public record Decide(UUID requestId, UUID staffId, Set<String> staffRoles, UUID staffMemberId, Decision decision,
                         String reasonCode, String note, long evidenceViewedMs, String currentSnapshotHash,
                         String idempotencyKey) {
        public Decide {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(staffId, "staffId");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(currentSnapshotHash, "currentSnapshotHash");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            staffRoles = Set.copyOf(staffRoles);
        }
    }

    /** {@code next} is {@code CHECKER} while a countersignature is still needed, otherwise null. */
    public record DecisionResult(UUID requestId, String requestStatus, String role, String next, long auditSeq) {
    }

    public record DecisionView(String role, String staffRef, Decision decision, String reasonCode, Instant decidedAt) {
    }

    public record ApprovalView(UUID id, UUID subjectId, Tier tier, String status, String snapshotHash, Instant slaDueAt,
                               boolean checkerRequired, Instant openedAt, Instant closedAt, String closedReason,
                               List<DecisionView> decisions) {
        public ApprovalView {
            decisions = List.copyOf(decisions);
        }
    }

    /** Input to the rubber-stamp detector: how fast, and after how much evidence, one officer decided. */
    public record DecisionStat(boolean approve, long decidedMs, long evidenceViewedMs, Instant decidedAt) {
    }

    public record AuditCommand(String entryType, UUID subjectId, String actorType, String actorId, String payloadJson) {
        public AuditCommand {
            Objects.requireNonNull(entryType, "entryType");
            Objects.requireNonNull(actorType, "actorType");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(payloadJson, "payloadJson");
        }
    }

    /** {@code firstBrokenSeq} is null when the chain is intact. */
    public record ChainVerification(boolean intact, long entries, Long firstBrokenSeq) {
    }
}
