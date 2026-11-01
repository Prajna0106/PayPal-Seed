package com.paypalseed.engines.rules.hardship;

/** Ordered: a later constant is always more serious. A case opens at {@link #LIKELY_MISS} or above. */
public enum RiskLevel {
    NONE,
    WATCH,
    LIKELY_MISS,
    IMMINENT;

    /** One step up; {@link #IMMINENT} is the top. */
    public RiskLevel next() {
        return this == IMMINENT ? IMMINENT : values()[ordinal() + 1];
    }

    public boolean atLeast(RiskLevel other) {
        return compareTo(other) >= 0;
    }

    public static RiskLevel forPoints(int points, HardshipPolicy.Scoring s) {
        if (points >= s.imminentAt()) {
            return IMMINENT;
        }
        if (points >= s.likelyMissAt()) {
            return LIKELY_MISS;
        }
        return points >= s.watchAt() ? WATCH : NONE;
    }

    /** The fewest points that reach this level. */
    public int threshold(HardshipPolicy.Scoring s) {
        return switch (this) {
            case NONE -> 0;
            case WATCH -> s.watchAt();
            case LIKELY_MISS -> s.likelyMissAt();
            case IMMINENT -> s.imminentAt();
        };
    }
}
