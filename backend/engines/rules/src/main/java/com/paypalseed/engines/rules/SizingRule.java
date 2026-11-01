package com.paypalseed.engines.rules;

import java.util.OptionalLong;

/**
 * Deterministic loan sizing and affordability rules (Blueprint §5).
 * Pure functions with zero I/O or framework dependencies.
 *
 * <p>The monthly instalment must be no more than BOTH:
 * <ul>
 *   <li>2 × the member's proven monthly saving amount</li>
 *   <li>15% of their verified average monthly income (when available)</li>
 * </ul>
 */
public final class SizingRule {

    public static final int SAVINGS_MULTIPLIER = 2;
    public static final int INCOME_CAP_PERCENT = 15;
    private static final long CENTS_PER_DOLLAR = 100L;

    private SizingRule() {
    }

    /**
     * Computes the maximum affordable monthly instalment in integer cents, floored to whole dollars.
     *
     * @param monthlySavingCents         proven monthly savings deposit in cents
     * @param verifiedMonthlyIncomeCents verified average monthly income in cents, if available
     * @return maximum monthly instalment in cents (whole dollars)
     */
    public static long maxMonthlyInstalmentCents(long monthlySavingCents, OptionalLong verifiedMonthlyIncomeCents) {
        if (monthlySavingCents <= 0) {
            return 0L;
        }
        long savingsCapCents = Math.multiplyExact(monthlySavingCents, SAVINGS_MULTIPLIER);
        long rawCapCents = savingsCapCents;

        if (verifiedMonthlyIncomeCents != null && verifiedMonthlyIncomeCents.isPresent()) {
            long incomeCents = verifiedMonthlyIncomeCents.getAsLong();
            if (incomeCents <= 0) {
                return 0L;
            }
            long incomeCapCents = Math.multiplyExact(incomeCents, INCOME_CAP_PERCENT) / 100L;
            rawCapCents = Math.min(savingsCapCents, incomeCapCents);
        }

        return (rawCapCents / CENTS_PER_DOLLAR) * CENTS_PER_DOLLAR;
    }

    /**
     * Computes the peak whole-dollar monthly instalment required for a given principal and term.
     */
    public static long requiredMonthlyInstalmentCents(long principalCents, int termMonths) {
        if (principalCents <= 0) {
            throw new IllegalArgumentException("principalCents must be positive");
        }
        if (termMonths <= 0) {
            throw new IllegalArgumentException("termMonths must be positive");
        }
        long principalDollars = (principalCents + CENTS_PER_DOLLAR - 1) / CENTS_PER_DOLLAR;
        long peakInstalmentDollars = (principalDollars + termMonths - 1) / termMonths;
        return Math.multiplyExact(peakInstalmentDollars, CENTS_PER_DOLLAR);
    }

    /**
     * Computes the largest safe loan principal (in cents) for a given term, bounded by the tier limit.
     */
    public static long maxSafePrincipalCents(long maxMonthlyInstalmentCents, int termMonths, long tierMaxLoanCents) {
        if (maxMonthlyInstalmentCents <= 0 || termMonths <= 0 || tierMaxLoanCents <= 0) {
            return 0L;
        }
        long affordablePrincipal = Math.multiplyExact(maxMonthlyInstalmentCents, termMonths);
        return Math.min(tierMaxLoanCents, affordablePrincipal);
    }

    /**
     * Computes the minimum number of months needed so that {@code requestedPrincipalCents} fits within
     * {@code maxMonthlyInstalmentCents}.
     */
    public static int minimumSafeTermMonths(long requestedPrincipalCents, long maxMonthlyInstalmentCents) {
        if (requestedPrincipalCents <= 0) {
            return 1;
        }
        if (maxMonthlyInstalmentCents <= 0) {
            return Integer.MAX_VALUE;
        }
        long months = (requestedPrincipalCents + maxMonthlyInstalmentCents - 1) / maxMonthlyInstalmentCents;
        return (int) Math.min(Integer.MAX_VALUE, months);
    }
}
