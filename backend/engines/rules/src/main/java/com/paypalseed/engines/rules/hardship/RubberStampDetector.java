package com.paypalseed.engines.rules.hardship;

import java.util.List;
import java.util.Objects;

/**
 * Meaningful human review is the control that makes the feature acceptable, so the system measures it
 * (03 §12). A flag only notifies and raises sampling; nothing is blocked automatically.
 */
public final class RubberStampDetector {

    public static final int WINDOW = 20;
    public static final long FAST_MS = 10_000;

    /** One officer decision, timed from opening the case panel. */
    public record Decision(boolean approve, long decidedMs, long evidenceViewedMs) {

        public Decision {
            HardshipPolicy.require(decidedMs >= 0 && evidenceViewedMs >= 0, "durations must not be negative");
        }
    }

    private RubberStampDetector() {
    }

    /**
     * Flags when at least half of the last {@value #WINDOW} decisions were fast (under 10 s with under 10 s of
     * evidence viewed) and at least 90% approved. Fewer than {@value #WINDOW} decisions never flag.
     *
     * @param decisions oldest first; only the last {@value #WINDOW} count
     */
    public static boolean flag(List<Decision> decisions) {
        Objects.requireNonNull(decisions, "decisions");
        if (decisions.size() < WINDOW) {
            return false;
        }
        List<Decision> window = decisions.subList(decisions.size() - WINDOW, decisions.size());
        long fast = window.stream().filter(d -> d.decidedMs() < FAST_MS && d.evidenceViewedMs() < FAST_MS).count();
        long approved = window.stream().filter(Decision::approve).count();
        return fast * 2 >= WINDOW && approved * 10 >= 9L * WINDOW;
    }
}
