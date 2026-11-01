package com.paypalseed.underwriting.domain.model;

import com.paypalseed.engines.assessment.AssessmentEngine.FairCheckResult;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Auditable record of a Feature 3 Fair Loan Check assessment (Blueprint §6).
 * Stores the deterministic numbers, reproducible inputs hash, plain-language explanation card,
 * member dispute notes, and the human loan officer decision.
 */
public class FairLoanCheckAssessment {

    public static final String MODEL_VERSION = "seed-fair-check-v1.0";

    public enum OfficerDecisionType {
        PENDING,
        APPROVED,
        ADJUSTED,
        DECLINED
    }

    private final UUID id;
    private final UUID memberId;
    private final UUID applicationId;
    private final String purpose;
    private final FairCheckResult result;
    private final String modelVersion;
    private final Instant createdAt;

    private boolean memberDisputed;
    private String memberDisputeNote;
    private Instant disputedAt;

    private OfficerDecisionType officerDecision;
    private UUID officerId;
    private long decidedLoanCents;
    private int decidedTermMonths;
    private String officerReason;
    private Instant decidedAt;

    public FairLoanCheckAssessment(
        UUID id,
        UUID memberId,
        UUID applicationId,
        String purpose,
        FairCheckResult result,
        String modelVersion,
        Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.memberId = Objects.requireNonNull(memberId, "memberId is required");
        this.applicationId = applicationId;
        this.purpose = purpose != null ? purpose.trim() : "";
        this.result = Objects.requireNonNull(result, "result is required");
        this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.officerDecision = OfficerDecisionType.PENDING;
        this.decidedLoanCents = result.suggestedLoanCents();
        this.decidedTermMonths = result.suggestedTermMonths();
    }

    public void restoreReviewState(
        boolean memberDisputed,
        String memberDisputeNote,
        Instant disputedAt,
        OfficerDecisionType officerDecision,
        UUID officerId,
        long decidedLoanCents,
        int decidedTermMonths,
        String officerReason,
        Instant decidedAt
    ) {
        this.memberDisputed = memberDisputed;
        this.memberDisputeNote = memberDisputeNote;
        this.disputedAt = disputedAt;
        this.officerDecision = officerDecision != null ? officerDecision : OfficerDecisionType.PENDING;
        this.officerId = officerId;
        this.decidedLoanCents = decidedLoanCents;
        this.decidedTermMonths = decidedTermMonths;
        this.officerReason = officerReason;
        this.decidedAt = decidedAt;
    }

    public static FairLoanCheckAssessment create(
        UUID memberId,
        UUID applicationId,
        String purpose,
        FairCheckResult result,
        Instant now
    ) {
        return new FairLoanCheckAssessment(
            Ids.newId(),
            memberId,
            applicationId,
            purpose,
            result,
            MODEL_VERSION,
            now
        );
    }

    /**
     * Blueprint §6 Edge cases: "Member disputes a number: they can mark it wrong;
     * the officer sees the note and can request other evidence."
     */
    public void flagMemberDispute(String note, Instant now) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Dispute note must not be empty");
        }
        this.memberDisputed = true;
        this.memberDisputeNote = note.trim();
        this.disputedAt = Objects.requireNonNull(now, "now is required");
    }

    /**
     * Blueprint §6 Step 5: "The officer approves, adjusts or declines, with a reason."
     */
    public void recordOfficerDecision(
        UUID officerId,
        OfficerDecisionType decision,
        Long adjustedLoanCents,
        Integer adjustedTermMonths,
        String reason,
        Instant now
    ) {
        Objects.requireNonNull(officerId, "officerId is required");
        Objects.requireNonNull(decision, "decision is required");
        if (decision == OfficerDecisionType.PENDING) {
            throw new DomainException(UnderwritingErrors.INVALID_DECISION, "Decision cannot be PENDING");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainException(UnderwritingErrors.INVALID_DECISION, "Officer decision requires a reason");
        }

        this.officerId = officerId;
        this.officerDecision = decision;
        this.officerReason = reason.trim();
        this.decidedAt = Objects.requireNonNull(now, "now is required");

        if (decision == OfficerDecisionType.DECLINED) {
            this.decidedLoanCents = 0L;
            this.decidedTermMonths = 0;
        } else if (decision == OfficerDecisionType.ADJUSTED) {
            this.decidedLoanCents = adjustedLoanCents != null ? adjustedLoanCents : result.suggestedLoanCents();
            this.decidedTermMonths = adjustedTermMonths != null ? adjustedTermMonths : result.suggestedTermMonths();
        } else {
            this.decidedLoanCents = result.suggestedLoanCents();
            this.decidedTermMonths = result.suggestedTermMonths();
        }
    }

    public UUID getId() { return id; }
    public UUID getMemberId() { return memberId; }
    public UUID getApplicationId() { return applicationId; }
    public String getPurpose() { return purpose; }
    public FairCheckResult getResult() { return result; }
    public String getModelVersion() { return modelVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isMemberDisputed() { return memberDisputed; }
    public String getMemberDisputeNote() { return memberDisputeNote; }
    public Instant getDisputedAt() { return disputedAt; }
    public OfficerDecisionType getOfficerDecision() { return officerDecision; }
    public UUID getOfficerId() { return officerId; }
    public long getDecidedLoanCents() { return decidedLoanCents; }
    public int getDecidedTermMonths() { return decidedTermMonths; }
    public String getOfficerReason() { return officerReason; }
    public Instant getDecidedAt() { return decidedAt; }
}
