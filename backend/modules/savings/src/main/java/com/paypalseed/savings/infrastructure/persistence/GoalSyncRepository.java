package com.paypalseed.savings.infrastructure.persistence;

import static com.paypalseed.savings.infrastructure.persistence.jooq.Tables.SAVINGS_GOAL;

import com.paypalseed.savings.application.ports.GoalSyncLog;
import com.paypalseed.savings.domain.model.GoalStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code savings.savings_goal.last_synced_at}: kept apart from {@link SavingsRepository}, whose save never touches it. */
@Repository
public class GoalSyncRepository implements GoalSyncLog {

    private final DSLContext dsl;

    public GoalSyncRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean claim(UUID goalId, Instant now, Duration minGap) {
        // One statement: the row lock makes a concurrent claim wait, then fail the condition against the new time.
        return dsl.update(SAVINGS_GOAL)
                .set(SAVINGS_GOAL.LAST_SYNCED_AT, now)
                .where(SAVINGS_GOAL.ID.eq(goalId))
                .and(SAVINGS_GOAL.LAST_SYNCED_AT.isNull().or(SAVINGS_GOAL.LAST_SYNCED_AT.le(now.minus(minGap))))
                .execute() == 1;
    }

    @Override
    public List<UUID> due(Instant approvalNow, Instant readBefore, int limit) {
        return dsl.select(SAVINGS_GOAL.ID)
                .from(SAVINGS_GOAL)
                .where(SAVINGS_GOAL.PAYPAL_SUBSCRIPTION_ID.isNotNull())
                .and(SAVINGS_GOAL.STATUS.eq(GoalStatus.PENDING_APPROVAL.name())
                        .and(SAVINGS_GOAL.APPROVAL_EXPIRES_AT.isNull().or(SAVINGS_GOAL.APPROVAL_EXPIRES_AT.gt(approvalNow)))
                        .or(SAVINGS_GOAL.STATUS.in(GoalStatus.ACTIVE.name(), GoalStatus.PAUSED.name())
                                .and(SAVINGS_GOAL.LAST_SYNCED_AT.isNull().or(SAVINGS_GOAL.LAST_SYNCED_AT.le(readBefore)))))
                .orderBy(SAVINGS_GOAL.LAST_SYNCED_AT.asc().nullsFirst())
                .limit(limit)
                .fetch(SAVINGS_GOAL.ID);
    }
}
