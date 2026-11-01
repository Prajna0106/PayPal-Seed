package com.paypalseed.agentgateway.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The automatic kill-switch rules for one agent (04 §10): more than 20% validator rejections over the
 * last 50 verdicts (judged once at least 10 are in, so a cold start trips on a real pattern, not on the
 * first rejection), or more than 3 grounding failures within one hour. Thread-safe.
 */
public final class TripRules {

    static final int WINDOW = 50;
    static final int MIN_SAMPLE = 10;
    static final int MAX_REJECTION_PERCENT = 20;
    static final int MAX_GROUNDING_FAILURES_PER_HOUR = 3;

    private final Deque<Boolean> verdicts = new ArrayDeque<>();
    private final Deque<Instant> groundingFailures = new ArrayDeque<>();

    /** Records a verdict; true when the rejection rule now says trip. */
    public synchronized boolean recordVerdict(boolean rejected) {
        verdicts.addLast(rejected);
        if (verdicts.size() > WINDOW) {
            verdicts.removeFirst();
        }
        if (verdicts.size() < MIN_SAMPLE) {
            return false;
        }
        long rejections = verdicts.stream().filter(Boolean::booleanValue).count();
        return rejections * 100 > (long) MAX_REJECTION_PERCENT * verdicts.size();
    }

    /** Records a grounding failure at {@code at}; true when more than 3 fell within the last hour. */
    public synchronized boolean recordGroundingFailure(Instant at) {
        groundingFailures.addLast(at);
        Instant cutoff = at.minus(Duration.ofHours(1));
        while (!groundingFailures.isEmpty() && groundingFailures.peekFirst().isBefore(cutoff)) {
            groundingFailures.removeFirst();
        }
        return groundingFailures.size() > MAX_GROUNDING_FAILURES_PER_HOUR;
    }

    /** After a person resets the mode, old evidence must not trip it again at once. */
    public synchronized void clear() {
        verdicts.clear();
        groundingFailures.clear();
    }
}
