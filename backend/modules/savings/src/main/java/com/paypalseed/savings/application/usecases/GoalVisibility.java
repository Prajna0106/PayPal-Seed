package com.paypalseed.savings.application.usecases;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Tells a goal that is about to become visible from a goal that never will. A PayPal webhook can reach the listener before
 * the transaction that created the goal has committed; waiting a moment fixes that. After {@code window} since the event
 * happened, a goal that is still missing is not a race any more, and the event is dropped instead of being delivered
 * again for ever. The window is the poison limit: the registry retries about once a minute, so the default hour is about
 * sixty tries.
 *
 * <p>The time is real time on purpose, like every time PayPal dates (ADR 0027): the business clock can run ahead in a demo.
 */
public final class GoalVisibility {

    public static final Duration DEFAULT_WINDOW = Duration.ofHours(1);

    private final Clock realTime;
    private final Duration window;

    public GoalVisibility(Clock realTime, Duration window) {
        this.realTime = Objects.requireNonNull(realTime);
        this.window = Objects.requireNonNull(window);
        if (window.isNegative()) {
            throw new IllegalArgumentException("the goal visibility window cannot be negative");
        }
    }

    /** A window of zero never waits: a missing goal is dropped at once. */
    public static GoalVisibility never() {
        return new GoalVisibility(Clock.systemUTC(), Duration.ZERO);
    }

    /** True while the event is younger than the window (an event dated in the future counts as young). */
    public boolean worthWaitingFor(Instant eventHappenedAt) {
        return !window.isZero() && eventHappenedAt != null && Duration.between(eventHappenedAt, realTime.instant()).compareTo(window) < 0;
    }
}
