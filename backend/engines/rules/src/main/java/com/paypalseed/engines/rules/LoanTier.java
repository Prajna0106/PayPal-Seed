package com.paypalseed.engines.rules;

import com.paypalseed.platform.kernel.Money;

/**
 * Graduated 0% microloan ladder (Blueprint §5).
 * Saving replaces a credit score as proof of capacity and habit to repay.
 */
public enum LoanTier {

    UNQUALIFIED(
        0,
        0L,
        0,
        0,
        "Requires at least 3 months of steady saving with at most 1 missed deposit per quarter"
    ),
    LEVEL_1(
        1,
        150_000L, // $1,500.00
        3,
        12,
        "3+ months of steady saving unlocked Level 1: up to $1,500 over 3 to 12 months at 0% interest, no fees"
    ),
    LEVEL_2(
        2,
        300_000L, // $3,000.00
        3,
        12,
        "Level 1 loan repaid on time + active saving unlocked Level 2: up to $3,000 over 3 to 12 months at 0% interest, no fees"
    ),
    LEVEL_3(
        3,
        500_000L, // $5,000.00
        6,
        18,
        "Level 2 loan repaid on time + active saving unlocked Level 3: up to $5,000 over 6 to 18 months at 0% interest, no fees"
    );

    private final int level;
    private final long maxLoanCents;
    private final int minTermMonths;
    private final int maxTermMonths;
    private final String description;

    LoanTier(int level, long maxLoanCents, int minTermMonths, int maxTermMonths, String description) {
        this.level = level;
        this.maxLoanCents = maxLoanCents;
        this.minTermMonths = minTermMonths;
        this.maxTermMonths = maxTermMonths;
        this.description = description;
    }

    public int level() {
        return level;
    }

    public long maxLoanCents() {
        return maxLoanCents;
    }

    public Money maxLoanMoney() {
        return Money.ofCents(maxLoanCents);
    }

    public int minTermMonths() {
        return minTermMonths;
    }

    public int maxTermMonths() {
        return maxTermMonths;
    }

    public String description() {
        return description;
    }

    public boolean isQualified() {
        return this != UNQUALIFIED;
    }

    public boolean isValidTerm(int termMonths) {
        return isQualified() && termMonths >= minTermMonths && termMonths <= maxTermMonths;
    }

    /**
     * Blueprint §5 Edge cases: "One loan at a time at Level 1 and 2."
     */
    public boolean allowsConcurrentLoans() {
        return false;
    }
}
