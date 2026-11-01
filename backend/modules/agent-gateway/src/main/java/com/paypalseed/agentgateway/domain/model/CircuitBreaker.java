package com.paypalseed.agentgateway.domain.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A per-model circuit breaker. It opens after {@code failureThreshold} consecutive failures, rejects calls
 * for {@code openFor}, then lets a single probe through (half-open): a success closes it, a failure opens
 * it again. Thread-safe; the probe slot is handed out once.
 */
public final class CircuitBreaker {

    public enum State {
        CLOSED,
        HALF_OPEN,
        OPEN
    }

    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;

    public CircuitBreaker(int failureThreshold, Duration openFor, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be at least 1");
        }
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    /** True when a call may go to this model now. In half-open, only the first caller gets the probe. */
    public synchronized boolean allowRequest() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openFor))) {
            state = State.HALF_OPEN;
            probeInFlight = false;
        }
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                if (probeInFlight) {
                    yield false;
                }
                probeInFlight = true;
                yield true;
            }
        };
    }

    public synchronized void recordSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
            probeInFlight = false;
        }
    }

    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openFor))) {
            return State.HALF_OPEN;
        }
        return state;
    }
}
