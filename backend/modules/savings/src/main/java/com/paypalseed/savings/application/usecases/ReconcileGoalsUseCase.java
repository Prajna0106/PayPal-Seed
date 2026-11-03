package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.savings.application.ports.GoalSyncLog;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The safety net behind {@link SyncGoalUseCase} (ADR 0028): reads the PayPal subscription of every goal that may have
 * missed a webhook, a bounded batch at a time. A goal that fails is logged and skipped, so one bad subscription never
 * stops the others; it is tried again by the next run.
 */
public class ReconcileGoalsUseCase {

    public record Result(int examined, int synced, int failed) {
    }

    private static final Logger log = LoggerFactory.getLogger(ReconcileGoalsUseCase.class);

    private final GoalSyncLog syncLog;
    private final SyncGoalUseCase sync;
    private final BusinessClock businessClock;
    private final Clock realTime;
    private final Duration readGap;

    public ReconcileGoalsUseCase(GoalSyncLog syncLog, SyncGoalUseCase sync, BusinessClock businessClock, Clock realTime,
                                 Duration readGap) {
        this.syncLog = Objects.requireNonNull(syncLog);
        this.sync = Objects.requireNonNull(sync);
        this.businessClock = Objects.requireNonNull(businessClock);
        this.realTime = Objects.requireNonNull(realTime);
        this.readGap = Objects.requireNonNull(readGap);
    }

    public Result run(int limit) {
        // The approval window is a business-time fact (the goal's own expiry); how long ago PayPal was read is real time.
        List<UUID> due = syncLog.due(businessClock.now(), realTime.instant().minus(readGap), limit);
        int synced = 0;
        int failed = 0;
        for (UUID goalId : due) {
            try {
                if (sync.execute(goalId).read()) {
                    synced++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Could not read the PayPal subscription of savings goal {}: {}", goalId, e.toString());
            }
        }
        return new Result(due.size(), synced, failed);
    }
}
