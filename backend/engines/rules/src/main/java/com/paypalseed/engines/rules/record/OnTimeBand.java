package com.paypalseed.engines.rules.record;

/**
 * The on-time share as a coarse band. A partner sees the band, never the exact percentage, so a record shared
 * today cannot be matched to an exact count tomorrow.
 */
public enum OnTimeBand {

    DEVELOPING("below 70%", 0),
    FAIR("70-84%", 7_000),
    STRONG("85-94%", 8_500),
    EXCELLENT("95-100%", 9_500);

    private final String label;
    private final int minBps;

    OnTimeBand(String label, int minBps) {
        this.label = label;
        this.minBps = minBps;
    }

    public String label() {
        return label;
    }

    public static OnTimeBand of(int onTimeBps) {
        OnTimeBand[] all = values();
        for (int i = all.length - 1; i >= 0; i--) {
            if (onTimeBps >= all[i].minBps) {
                return all[i];
            }
        }
        return DEVELOPING;
    }
}
