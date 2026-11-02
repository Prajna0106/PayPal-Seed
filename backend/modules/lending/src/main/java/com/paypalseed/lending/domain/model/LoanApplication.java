package com.paypalseed.lending.domain.model;

import com.paypalseed.engines.rules.LoanTier;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing a member's savings-linked microloan application (Blueprint §5).
 *
 * <p>Lifecycle:
 * {@code SUBMITTED -> PENDING_SECOND_APPROVAL -> APPROVED -> DISBURSING -> DISBURSED (-> CANCELLED_COOLING_OFF)}
 * or {@code REJECTED}. {@code DISBURSING} means the payout is with PayPal: the loan is registered only when
 * the payout succeeds, and a payout that fails for good puts the application back to {@code APPROVED} with the
 * reason in {@code payoutIssue}, ready to be sent again. Nothing can cancel or reject an application in between.
 */
public class LoanApplication {

    public static final Duration COOLING_OFF_WINDOW = Duration.ofHours(48);

    public enum ApplicationStatus {
        SUBMITTED,
        PENDING_SECOND_APPROVAL,
        APPROVED,
        /** The payout to the member has been asked of PayPal and has not finished. */
        DISBURSING,
        DISBURSED,
        REJECTED,
        CANCELLED_COOLING_OFF
    }

    public enum WalletType {
        PAYPAL,
        VENMO
    }

    private final UUID id;
    private final UUID memberId;
    private final String purpose;
    private final long requestedAmountCents;
    private final int termMonths;
    private final int preferredDueDay;
    private final WalletType payoutWallet;
    private final boolean fairCheckConsented;
    private final LoanTier unlockedTier;
    private final long maxMonthlyInstalmentCents;
    private final long monthlyInstalmentCents;
    private final List<String> sizingReasons;

    private ApplicationStatus status;
    private UUID firstApproverId;
    private Instant firstApprovedAt;
    private UUID secondApproverId;
    private Instant secondApprovedAt;
    private String rejectionReason;
    private UUID loanId;
    private String disbursementRef;
    private Instant disbursedAt;
    private Instant coolingOffExpiresAt;
    private String payoutIssue;
    private int payoutAttempts;
    private UUID lastOfficerId;
    private final Instant createdAt;
    private Instant updatedAt;

    public LoanApplication(
        UUID id,
        UUID memberId,
        String purpose,
        long requestedAmountCents,
        int termMonths,
        int preferredDueDay,
        WalletType payoutWallet,
        boolean fairCheckConsented,
        LoanTier unlockedTier,
        long maxMonthlyInstalmentCents,
        long monthlyInstalmentCents,
        List<String> sizingReasons,
        ApplicationStatus status,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.memberId = Objects.requireNonNull(memberId, "memberId is required");
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Loan purpose is required");
        }
        if (!fairCheckConsented) {
            throw new IllegalArgumentException("Consent to the fair loan check is required");
        }
        if (preferredDueDay < 1 || preferredDueDay > 28) {
            throw new IllegalArgumentException("preferredDueDay must be between 1 and 28");
        }
        this.purpose = purpose.trim();
        this.requestedAmountCents = requestedAmountCents;
        this.termMonths = termMonths;
        this.preferredDueDay = preferredDueDay;
        this.payoutWallet = Objects.requireNonNullElse(payoutWallet, WalletType.PAYPAL);
        this.fairCheckConsented = true;
        this.unlockedTier = Objects.requireNonNull(unlockedTier, "unlockedTier is required");
        this.maxMonthlyInstalmentCents = maxMonthlyInstalmentCents;
        this.monthlyInstalmentCents = monthlyInstalmentCents;
        this.sizingReasons = sizingReasons != null ? List.copyOf(sizingReasons) : List.of();
        this.status = Objects.requireNonNull(status, "status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    public void restoreLifecycleState(
        UUID firstApproverId,
        Instant firstApprovedAt,
        UUID secondApproverId,
        Instant secondApprovedAt,
        String rejectionReason,
        UUID loanId,
        String disbursementRef,
        Instant disbursedAt,
        Instant coolingOffExpiresAt
    ) {
        this.firstApproverId = firstApproverId;
        this.firstApprovedAt = firstApprovedAt;
        this.secondApproverId = secondApproverId;
        this.secondApprovedAt = secondApprovedAt;
        this.rejectionReason = rejectionReason;
        this.loanId = loanId;
        this.disbursementRef = disbursementRef;
        this.disbursedAt = disbursedAt;
        this.coolingOffExpiresAt = coolingOffExpiresAt;
    }

    /** Restores what a stored application knows beyond the original lifecycle fields. */
    public void restorePayoutState(String payoutIssue, int payoutAttempts, UUID lastOfficerId) {
        this.payoutIssue = payoutIssue;
        this.payoutAttempts = payoutAttempts;
        this.lastOfficerId = lastOfficerId;
    }

    public static LoanApplication submit(
        UUID memberId,
        String purpose,
        long requestedAmountCents,
        int termMonths,
        int preferredDueDay,
        WalletType payoutWallet,
        boolean fairCheckConsented,
        LoanTier unlockedTier,
        long maxMonthlyInstalmentCents,
        long monthlyInstalmentCents,
        List<String> sizingReasons,
        Instant now
    ) {
        return new LoanApplication(
            Ids.newId(),
            memberId,
            purpose,
            requestedAmountCents,
            termMonths,
            preferredDueDay,
            payoutWallet,
            fairCheckConsented,
            unlockedTier,
            maxMonthlyInstalmentCents,
            monthlyInstalmentCents,
            sizingReasons,
            ApplicationStatus.SUBMITTED,
            now,
            now
        );
    }

    /**
     * Records a loan officer approval. Blueprint §5 requires two-person approval (maker-checker)
     * in the desk with both officer identities stored.
     */
    public void approve(UUID officerId, Instant now) {
        Objects.requireNonNull(officerId, "officerId is required");
        Objects.requireNonNull(now, "now is required");

        this.lastOfficerId = officerId;
        if (this.status == ApplicationStatus.SUBMITTED) {
            this.firstApproverId = officerId;
            this.firstApprovedAt = now;
            this.status = ApplicationStatus.PENDING_SECOND_APPROVAL;
            this.updatedAt = now;
            return;
        }

        if (this.status == ApplicationStatus.PENDING_SECOND_APPROVAL) {
            if (this.firstApproverId.equals(officerId)) {
                throw new DomainException(
                    LendingErrors.MAKER_CHECKER_SAME_OFFICER,
                    "Second approver must be a different loan officer from the first approver"
                );
            }
            this.secondApproverId = officerId;
            this.secondApprovedAt = now;
            this.status = ApplicationStatus.APPROVED;
            this.updatedAt = now;
            return;
        }

        throw new DomainException(
            LendingErrors.APPLICATION_INVALID_STATE,
            "Cannot approve application in status " + this.status
        );
    }

    public void reject(UUID officerId, String reason, Instant now) {
        Objects.requireNonNull(officerId, "officerId is required");
        Objects.requireNonNull(now, "now is required");
        if (this.status != ApplicationStatus.SUBMITTED && this.status != ApplicationStatus.PENDING_SECOND_APPROVAL) {
            throw new DomainException(
                LendingErrors.APPLICATION_INVALID_STATE,
                "Cannot reject application in status " + this.status
            );
        }
        this.rejectionReason = reason != null ? reason.trim() : "Declined during desk review";
        this.lastOfficerId = officerId;
        this.status = ApplicationStatus.REJECTED;
        this.updatedAt = now;
    }

    /**
     * Blueprint §5: Payouts with {@code sender_batch_id = loan-{id}-disb}.
     */
    public String expectedDisbursementBatchId() {
        return "loan-" + this.id + "-disb";
    }

    /**
     * The reference of the current payout at PayPal. The first is the application id; PayPal spends a reference when
     * it refuses a payout, so every later attempt gets a suffix. Payout events are matched on it, which keeps a late
     * report about an earlier attempt away from the current one.
     */
    public String payoutReference() {
        return payoutAttempts <= 1 ? id.toString() : id + "-a" + payoutAttempts;
    }

    /**
     * The payout has been asked of PayPal. From here the application cannot be rejected or cancelled: the money may
     * already be on its way. Asking again while {@code DISBURSING} is allowed and changes nothing.
     */
    public void startDisbursement(UUID officerId, Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (this.status == ApplicationStatus.DISBURSING) {
            return;
        }
        if (this.status != ApplicationStatus.APPROVED) {
            throw new DomainException(
                LendingErrors.APPLICATION_INVALID_STATE,
                "Loan application must have two-person approval (APPROVED) before disbursement; current status: " + this.status
            );
        }
        if (officerId != null) {
            this.lastOfficerId = officerId;
        }
        this.disbursementRef = expectedDisbursementBatchId();
        this.payoutIssue = null;
        this.payoutAttempts++;
        this.status = ApplicationStatus.DISBURSING;
        this.updatedAt = now;
    }

    /**
     * The payout failed for good, or came back unclaimed: the application is approved again, with the reason where
     * an officer can read it, and can be disbursed once more.
     */
    public void payoutFailed(String reason, Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (this.status != ApplicationStatus.DISBURSING) {
            throw new DomainException(
                LendingErrors.APPLICATION_INVALID_STATE,
                "Only a disbursing application can have its payout fail; current status: " + this.status
            );
        }
        this.payoutIssue = reason == null || reason.isBlank() ? "PAYOUT_FAILED" : reason.trim();
        this.status = ApplicationStatus.APPROVED;
        this.updatedAt = now;
    }

    /** Notes what PayPal said without changing the state (for example money held for a recipient with no account yet). */
    public void notePayoutIssue(String issue, Instant now) {
        this.payoutIssue = issue;
        this.updatedAt = now;
    }

    public void markDisbursed(UUID registeredLoanId, String batchId, Instant now) {
        Objects.requireNonNull(registeredLoanId, "registeredLoanId is required");
        Objects.requireNonNull(batchId, "batchId is required");
        Objects.requireNonNull(now, "now is required");

        if (this.status != ApplicationStatus.APPROVED && this.status != ApplicationStatus.DISBURSING) {
            throw new DomainException(
                LendingErrors.APPLICATION_INVALID_STATE,
                "Loan application must have two-person approval (APPROVED) before disbursement; current status: " + this.status
            );
        }

        this.payoutIssue = null;
        this.loanId = registeredLoanId;
        this.disbursementRef = batchId;
        this.disbursedAt = now;
        this.coolingOffExpiresAt = now.plus(COOLING_OFF_WINDOW);
        this.status = ApplicationStatus.DISBURSED;
        this.updatedAt = now;
    }

    /**
     * Blueprint §5 User flow #7: "A 48-hour cooling-off lets her return the money with no cost if she changes her mind."
     */
    public void cancelWithinCoolingOff(Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (this.status != ApplicationStatus.DISBURSED) {
            throw new DomainException(
                LendingErrors.APPLICATION_INVALID_STATE,
                "Cooling-off cancellation only applies to DISBURSED loans; current status: " + this.status
            );
        }
        if (this.coolingOffExpiresAt != null && now.isAfter(this.coolingOffExpiresAt)) {
            throw new DomainException(
                LendingErrors.COOLING_OFF_EXPIRED,
                "The 48-hour cooling-off window expired at " + this.coolingOffExpiresAt
            );
        }
        this.status = ApplicationStatus.CANCELLED_COOLING_OFF;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getMemberId() { return memberId; }
    public String getPurpose() { return purpose; }
    public long getRequestedAmountCents() { return requestedAmountCents; }
    public int getTermMonths() { return termMonths; }
    public int getPreferredDueDay() { return preferredDueDay; }
    public WalletType getPayoutWallet() { return payoutWallet; }
    public boolean isFairCheckConsented() { return fairCheckConsented; }
    public LoanTier getUnlockedTier() { return unlockedTier; }
    public long getMaxMonthlyInstalmentCents() { return maxMonthlyInstalmentCents; }
    public long getMonthlyInstalmentCents() { return monthlyInstalmentCents; }
    public List<String> getSizingReasons() { return sizingReasons; }
    public ApplicationStatus getStatus() { return status; }
    public UUID getFirstApproverId() { return firstApproverId; }
    public Instant getFirstApprovedAt() { return firstApprovedAt; }
    public UUID getSecondApproverId() { return secondApproverId; }
    public Instant getSecondApprovedAt() { return secondApprovedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public UUID getLoanId() { return loanId; }
    public String getDisbursementRef() { return disbursementRef; }
    public Instant getDisbursedAt() { return disbursedAt; }
    public Instant getCoolingOffExpiresAt() { return coolingOffExpiresAt; }
    public String getPayoutIssue() { return payoutIssue; }
    public int getPayoutAttempts() { return payoutAttempts; }
    public UUID getLastOfficerId() { return lastOfficerId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
