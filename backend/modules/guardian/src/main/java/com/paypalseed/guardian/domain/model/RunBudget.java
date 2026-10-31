package com.paypalseed.guardian.domain.model;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The limits of one run (04 §3): six tool calls, 45 seconds overall, 20 seconds per model call. Checked
 * here and again by the gateway, so a bug on either side cannot lift them.
 */
public final class RunBudget {

    public static final int MAX_TOOL_CALLS = 6;
    public static final Duration RUN_LIMIT = Duration.ofSeconds(45);
    public static final Duration CALL_LIMIT = Duration.ofSeconds(20);
    /** Below this, a model call is not worth starting. */
    private static final Duration MIN_CALL = Duration.ofSeconds(2);

    private final LongSupplier nanoClock;
    private final long deadline;
    private int toolCalls;

    public RunBudget(LongSupplier nanoClock, Duration limit) {
        this.nanoClock = nanoClock;
        this.deadline = nanoClock.getAsLong() + limit.toNanos();
    }

    public static RunBudget start() {
        return new RunBudget(System::nanoTime, RUN_LIMIT);
    }

    /** The timeout for the next model call; throws {@code TIMEOUT} when too little time is left. */
    public Duration nextCallTimeout() {
        Duration left = Duration.ofNanos(deadline - nanoClock.getAsLong());
        if (left.compareTo(MIN_CALL) < 0) {
            throw new RunFailure(RunFailure.Code.TIMEOUT, "The run used its " + RUN_LIMIT.toSeconds() + " seconds");
        }
        return left.compareTo(CALL_LIMIT) < 0 ? left : CALL_LIMIT;
    }

    public boolean toolCallAvailable() {
        return toolCalls < MAX_TOOL_CALLS;
    }

    public int remainingToolCalls() {
        return MAX_TOOL_CALLS - toolCalls;
    }

    public void spendToolCall() {
        if (!toolCallAvailable()) {
            throw new IllegalStateException("tool call budget already spent");
        }
        toolCalls++;
    }
}
