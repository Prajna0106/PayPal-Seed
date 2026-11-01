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
        SAVINGS
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

    public record ApplicationRow(UUID applicationId, UUID memberRef, String status, long requestedAmountCents, int termMonths,
                                 int savingStreakMonths, String assessmentOutcome, Instant submittedAt, long ageDays,
                                 Instant slaDueAt, String slaState, boolean slaBreached, String assignedOfficerRef) {
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
}
