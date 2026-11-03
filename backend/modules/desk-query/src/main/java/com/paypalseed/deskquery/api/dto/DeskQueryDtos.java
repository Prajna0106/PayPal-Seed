package com.paypalseed.deskquery.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the desk lists and summaries return. Members appear only as opaque references; no name, email or contact detail
 * exists in these projections, so none can leak.
 */
public final class DeskQueryDtos {

    private DeskQueryDtos() {
    }

    /** Which projection to reset. */
    public enum Projection {
        APPLICATIONS,
        ASSESSMENTS,
        LOANS,
        HARDSHIP,
        APPROVALS,
        SAVINGS,
        /** The member Home activity list; replaying the publication log refills what the events carry. */
        ACTIVITY
    }

    /** A row count. {@code exact} is false once the table is too large to count and the value is the planner's estimate. */
    public record Total(long value, boolean exact) {
    }

    /**
     * One page. {@code nextCursor} is null on the last page. {@code total} is sent on the first page only, because a grid needs
     * it once. {@code asOf} is when the newest fact in the list was applied.
     */
    public record Page<T>(List<T> rows, String nextCursor, Total total, Instant asOf) {
    }

    /**
     * @param payoutIssue {@code RETURNED_AFTER_DISBURSEMENT} on an {@code APPROVED} application whose loan was voided
     *                    because PayPal took the payout back: it needs to be paid again (after the recipient is fixed) or
     *                    rejected. Null otherwise.
     */
    public record ApplicationRow(UUID applicationId, UUID memberRef, String status, long requestedAmountCents, int termMonths,
                                 int savingStreakMonths, String assessmentOutcome, Instant submittedAt, long ageDays,
                                 Instant slaDueAt, String slaState, boolean slaBreached, String assignedOfficerRef,
                                 String payoutIssue) {
    }

    public record AssessmentRow(UUID assessmentId, UUID memberRef, UUID applicationId, boolean recommended,
                                long recommendedAmountCents, String status, boolean disputed, Instant createdAt, long ageDays) {
    }

    public record LoanRow(UUID loanId, UUID memberRef, String status, long principalCents, long outstandingCents,
                          LocalDate nextDueDate, Long nextDueCents, int daysLate, String agingBucket, boolean autopay,
                          boolean hardship, LocalDate disbursedOn) {
    }

    public record StatusTotal(String status, long loans, long outstandingCents) {
    }

    public record BucketTotal(String bucket, long loans, long outstandingCents) {
    }

    public record OutcomeCount(String outcome, long cases) {
    }

    public record OnTimeRepayment(long instalmentsDue, long instalmentsMissed, Integer rateBasisPoints) {
    }

    /** Figures for the inclusion story. {@code membersLoanedWithoutCreditScore} equals {@code membersWithLoan}: by design. */
    public record InclusionImpact(long membersServed, long membersWithLoan, long membersLoanedWithoutCreditScore,
                                  long loansIssued, long averageLoanSizeCents, long firstLoansRepaid,
                                  long firstLoansRepaidOnPlan, Integer firstLoanOnPlanRateBasisPoints) {
    }

    public record PortfolioSummary(Instant asOf, long loans, long outstandingCents, List<StatusTotal> byStatus,
                                   List<BucketTotal> byAgingBucket, OnTimeRepayment onTimeRepayment,
                                   long openHardshipCases, List<OutcomeCount> hardshipOutcomes, InclusionImpact impact) {
    }

    /** One cell of the workload view: items of one kind, waiting for one role, in one SLA bucket. */
    public record WorkloadItem(String kind, String role, String slaBucket, long waiting, Instant earliestSlaDueAt) {
    }

    public record RoleWorkload(String role, long waiting, long overdue) {
    }

    public record Workload(Instant asOf, List<WorkloadItem> items, List<RoleWorkload> byRole) {
    }

    /**
     * One thing that happened in a member's Seed account. {@code kind} is one of DEPOSIT, WITHDRAWAL, WITHDRAWAL_RETURNED,
     * LOAN_DISBURSED, LOAN_PAYMENT, LOAN_PAYMENT_REVERSED, COVER_APPLIED (the safety fund paid on the loan) and LOAN_VOIDED
     * (the payout came back). {@code occurredAt} is the source's instant, or the start of the business day for a source that
     * gives only a date. {@code goalId} is set for savings kinds, {@code loanId} for loan kinds.
     */
    public record MemberActivityItem(String kind, long amountCents, Instant occurredAt, UUID goalId, UUID loanId) {
    }
}
