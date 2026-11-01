package com.paypalseed.engines.rules.hardship;

import java.util.Objects;

/** What the scorecard and the option ranker read from the member's consented weekly inflow (03 §2). */
public record IncomeFeatures(long baselineWeeklyCents, long baseline4Cents, long last4Cents, int dropBps,
                             long recentMonthlyCents, long coverageBps, int weeksBelow, Persistence persistence) {

    /** Whether recent weeks stay low (plan must shrink) or dipped briefly (smallest change is best). */
    public enum Persistence {
        PERSISTENT,
        TRANSIENT
    }

    public IncomeFeatures {
        HardshipPolicy.require(baselineWeeklyCents >= 0 && baseline4Cents >= 0 && last4Cents >= 0 && recentMonthlyCents >= 0
                && coverageBps >= 0, "income features must not be negative");
        HardshipPolicy.require(dropBps >= 0 && dropBps <= 10_000, "dropBps out of range");
        HardshipPolicy.require(weeksBelow >= 0, "weeksBelow must not be negative");
        Objects.requireNonNull(persistence, "persistence");
    }
}
