package com.paypalseed.savings.application.ports;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** When each goal's PayPal subscription was last read by Seed itself (ADR 0028), so a goal is read neither too often nor never. */
public interface GoalSyncLog {

    /**
     * Takes the right to read the goal now. True for exactly one of any number of concurrent callers, and only when the goal
     * was not read within {@code minGap} of {@code now}; the time is recorded before the read, so a read that fails still
     * counts and the next one waits.
     */
    boolean claim(UUID goalId, Instant now, Duration minGap);

    /**
     * Goals worth reading, least recently read first, at most {@code limit}: those waiting for approval whose window is still
     * open at {@code approvalNow}, and active or paused ones not read since {@code readBefore}. Only goals with a subscription.
     */
    List<UUID> due(Instant approvalNow, Instant readBefore, int limit);
}
