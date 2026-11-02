package com.paypalseed.lending.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LendingDtos {

    private LendingDtos() {
    }

    /** F2 calls this once the disbursement payout has succeeded. Idempotent on {@code disbursementRef}. */
    public record RegisterDisbursedLoan(UUID memberId, long principalCents, int instalmentCount, int dueDay,
                                        LocalDate disbursedOn, String disbursementRef) {
        public RegisterDisbursedLoan {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(disbursedOn, "disbursedOn");
            Objects.requireNonNull(disbursementRef, "disbursementRef");
        }
    }

    public enum ReschedulePrimitive {
        MOVE_DATE,
        DEFER_TO_END,
        REAMORTIZE
    }

    /** F5 hardship options A, B and C. {@code days} for MOVE_DATE, {@code extraMonths} for REAMORTIZE. */
    public record RescheduleRequest(UUID loanId, ReschedulePrimitive primitive, Integer seq, Integer days,
                                    Integer extraMonths) {
        public RescheduleRequest {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(primitive, "primitive");
        }
    }

    public record InstalmentLine(int seq, LocalDate dueDate, long amountCents, long paidCents, long remainingCents,
                                 String status, LocalDate paidOn) {
    }

    public record ScheduleView(UUID loanId, int version, String reason, LocalDate businessDate, long principalCents,
                               long outstandingCents, long overpaidCents, String loanStatus, String collectionMode,
                               int dpd, String bucket, List<InstalmentLine> instalments) {
    }

    public record Slot(int seq, LocalDate dueDate, long amountCents) {
    }

    public record ScheduleDiffView(List<Slot> removed, List<Slot> added) {
    }

    public record VersionView(int version, String reason, String causedBy, LocalDate createdOn, List<Slot> instalments,
                              Optional<ScheduleDiffView> diffFromPrevious) {
    }

    public record ReschedulePreview(ScheduleView current, ScheduleView proposed, ScheduleDiffView diff) {
    }

    public record OwedLine(int seq, LocalDate dueDate, long amountCents, long remainingCents) {
    }

    /**
     * What F5's scorecard needs from lending, computed fresh for {@code asOf}. The target is the oldest
     * instalment with money still owed; its fields are null when nothing is owed.
     */
    public record RiskFacts(UUID loanId, UUID memberId, String status, String bucket, int dpd, int scheduleVersion,
                            int dueDay, long outstandingCents, Integer targetSeq, LocalDate targetDueDate,
                            Long targetAmountCents, Long targetRemainingCents, int failedChargesLast14Days,
                            boolean failedTwiceOnTarget, boolean lateWithin90Days, long pendingCents, boolean hardshipOpen,
                            List<OwedLine> owed) {
        public RiskFacts {
            owed = List.copyOf(owed);
        }
    }

    public record LoanSummary(UUID loanId, UUID memberId, long principalCents, long outstandingCents, String status,
                              String collectionMode, String bucket, int dpd, int currentVersion) {
    }

    /**
     * A member's own loan as the Home page needs it. {@code nextDue} is the oldest instalment of the current
     * schedule with money still owed, and is null when the loan is not active or nothing is owed.
     * {@code autopay} says the loan is collected by autopay; the mandate's own state is payments' to tell.
     * Carries no days past due, bucket or risk fact.
     */
    public record MemberLoanView(UUID loanId, String status, long principalCents, long outstandingCents, NextDue nextDue,
                                 boolean autopay) {
    }

    public record NextDue(int seq, LocalDate dueDate, long amountCents, long remainingCents) {
    }

    /** A member's own application: what they asked for and where it stands. No officer ids, no sizing reasons. */
    public record MemberApplicationView(UUID applicationId, String status, long requestedAmountCents, int termMonths,
                                        Instant coolingOffExpiresAt, String payoutIssue) {
    }

    /**
     * The reserve paid {@code amountCents} of a loan. The ledger entry {@code journalEntryId} already credits the loan's
     * receivable, so lending applies the money to the schedule and posts nothing. {@code approvalRef} is the
     * idempotency key; {@code businessDate} is the date reserve booked the cover on, and the date it counts from.
     */
    public record ReserveCover(UUID loanId, long amountCents, String approvalRef, UUID journalEntryId, LocalDate businessDate,
                               Instant occurredAt) {
        public ReserveCover {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(approvalRef, "approvalRef");
            Objects.requireNonNull(journalEntryId, "journalEntryId");
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (amountCents <= 0) {
                throw new IllegalArgumentException("a cover is a positive amount");
            }
        }
    }

    public enum CoverOutcome {
        /** The cover was applied (all of it, or the part the loan still owed). */
        APPLIED,
        /** This approvalRef was applied before; nothing changed. */
        ALREADY_APPLIED,
        /** The loan owed nothing when the cover arrived (it was paid in the meantime); nothing was applied. */
        NOTHING_DUE
    }

    /**
     * {@code appliedCents} went to the schedule; {@code unusedCents} is the part of the cover the loan no longer owed.
     * The ledger credited the whole cover, so a non-zero {@code unusedCents} is a difference the desk must look at.
     */
    public record CoverResult(CoverOutcome outcome, long appliedCents, long unusedCents, long outstandingCents) {
    }

    /** Feature 2: Member's current microloan ladder tier and deterministic sizing bounds. */
    public record EligibilityView(
        UUID memberId,
        boolean eligible,
        String tier,
        int tierLevel,
        long tierLimitCents,
        long maxLoanCents,
        long maxMonthlyInstalmentCents,
        int minTermMonths,
        int maxTermMonths,
        int currentSavingStreakMonths,
        long monthlySavingCents,
        boolean hasActiveLoan,
        int completedLoansOnTime,
        List<String> reasons,
        /** The level of the member's credit record that was taken into account (NEW, BUILDING, STEADY, TRUSTED, EXEMPLARY). */
        String recordLevel,
        /** Which limit applied and why, in plain words. Never a score. */
        String limitReason
    ) {
        /** The view before the credit record took part: a NEW record and no explanation beyond {@code reasons}. */
        public EligibilityView(UUID memberId, boolean eligible, String tier, int tierLevel, long tierLimitCents, long maxLoanCents,
                               long maxMonthlyInstalmentCents, int minTermMonths, int maxTermMonths, int currentSavingStreakMonths,
                               long monthlySavingCents, boolean hasActiveLoan, int completedLoansOnTime, List<String> reasons) {
            this(memberId, eligible, tier, tierLevel, tierLimitCents, maxLoanCents, maxMonthlyInstalmentCents, minTermMonths,
                maxTermMonths, currentSavingStreakMonths, monthlySavingCents, hasActiveLoan, completedLoansOnTime, reasons, "NEW", "");
        }
    }

    /** Feature 2: Representation of a savings-linked microloan application. */
    public record LoanApplicationView(
        UUID id,
        UUID memberId,
        String purpose,
        long requestedAmountCents,
        int termMonths,
        int preferredDueDay,
        String payoutWallet,
        boolean fairCheckConsented,
        String unlockedTier,
        long maxMonthlyInstalmentCents,
        long monthlyInstalmentCents,
        String status,
        UUID firstApproverId,
        UUID secondApproverId,
        String rejectionReason,
        UUID loanId,
        String disbursementRef,
        Instant coolingOffExpiresAt,
        List<String> sizingReasons,
        Instant createdAt,
        Instant updatedAt,
        /** Why the last payout did not complete the disbursement (a PayPal reason name); null when there is none. */
        String payoutIssue
    ) {
    }
}
