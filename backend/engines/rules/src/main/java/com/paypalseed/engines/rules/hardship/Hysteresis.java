package com.paypalseed.engines.rules.hardship;

import java.util.Objects;

/**
 * Levels rise at once and fall only after {@code hysteresisScans} consecutive scans below the stored
 * level (03 §3.1), so one good week does not flip an alert off and on.
 */
public final class Hysteresis {

    public record State(RiskLevel level, int belowCount) {

        public State {
            Objects.requireNonNull(level, "level");
            HardshipPolicy.require(belowCount >= 0, "belowCount must not be negative");
        }
    }

    private Hysteresis() {
    }

    /**
     * @param previous   the level stored by the last scan of this instalment cycle; null for a new cycle
     * @param belowCount consecutive earlier scans whose raw level was below {@code previous}
     */
    public static State apply(RiskLevel raw, RiskLevel previous, int belowCount, HardshipPolicy policy) {
        Objects.requireNonNull(raw, "raw");
        HardshipPolicy.require(belowCount >= 0, "belowCount must not be negative");
        RiskLevel stored = previous == null ? RiskLevel.NONE : previous;
        if (raw.atLeast(stored)) {
            return new State(raw, 0);
        }
        int below = belowCount + 1;
        return below >= policy.scoring().hysteresisScans() ? new State(raw, 0) : new State(stored, below);
    }
}
