package com.paypalseed.deskquery.domain.model;

/**
 * Inclusion impact: who Seed reaches and whether they finish what they start. All figures derive from events; none comes
 * from a credit bureau, and none could, because no loan decision uses a credit score.
 */
public record ImpactFigures(
        long membersServed,
        long membersWithLoan,
        long membersLoanedWithoutCreditScore,
        long loansIssued,
        long averageLoanSizeCents,
        long firstLoansRepaid,
        long firstLoansRepaidOnPlan,
        Integer firstLoanOnPlanRateBasisPoints) {

    /** The counts the repository reads; {@link #of(Raw)} turns them into the figures. */
    public record Raw(long membersServed, long membersWithLoan, long loansIssued, long principalTotalCents,
                      long firstLoansRepaid, long firstLoansRepaidOnPlan) {
    }

    public static ImpactFigures of(Raw raw) {
        return new ImpactFigures(
                raw.membersServed(),
                raw.membersWithLoan(),
                // Every borrower, by design: eligibility rests on saving history and consented income, not a score.
                raw.membersWithLoan(),
                raw.loansIssued(),
                raw.loansIssued() == 0 ? 0 : roundedDivide(raw.principalTotalCents(), raw.loansIssued()),
                raw.firstLoansRepaid(),
                Math.min(raw.firstLoansRepaidOnPlan(), raw.firstLoansRepaid()),
                raw.firstLoansRepaid() == 0 ? null
                        : (int) roundedDivide(Math.min(raw.firstLoansRepaidOnPlan(), raw.firstLoansRepaid()) * 10_000,
                                raw.firstLoansRepaid()));
    }

    /** Round half up, for non-negative numerators. */
    static long roundedDivide(long numerator, long denominator) {
        return (numerator + denominator / 2) / denominator;
    }
}
