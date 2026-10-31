package com.paypalseed.engines.schedule;

/**
 * Delinquency buckets by days past due (plan §7; Fineract's delinquency ranges). A loan's bucket is
 * derived from its current schedule version, never stored as the source of truth.
 */
public enum Bucket {
    CURRENT(0, 0),
    DPD_1_7(1, 7),
    DPD_8_30(8, 30),
    DPD_31_60(31, 60),
    DPD_60_PLUS(61, Integer.MAX_VALUE);

    private final int minDpd;
    private final int maxDpd;

    Bucket(int minDpd, int maxDpd) {
        this.minDpd = minDpd;
        this.maxDpd = maxDpd;
    }

    public static Bucket of(int dpd) {
        if (dpd < 0) {
            throw new IllegalArgumentException("days past due cannot be negative");
        }
        for (Bucket b : values()) {
            if (dpd <= b.maxDpd) {
                return b;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** First day past due of this bucket; 0 for CURRENT. */
    public int minDpd() {
        return minDpd;
    }

    public boolean isDelinquent() {
        return this != CURRENT;
    }
}
