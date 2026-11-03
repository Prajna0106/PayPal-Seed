package com.paypalseed.deskquery.infrastructure.persistence;

import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_ACTIVITY;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.MemberActivityItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code deskquery.member_activity}: one row per business key, written once, so a repeated delivery changes nothing. */
@Repository
public class ActivityRepository {

    private final DSLContext dsl;

    public ActivityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void record(String activityKey, UUID memberRef, String kind, long amountCents, Instant occurredAt, UUID goalId,
                       UUID loanId) {
        dsl.insertInto(MEMBER_ACTIVITY)
                .set(MEMBER_ACTIVITY.ACTIVITY_KEY, activityKey)
                .set(MEMBER_ACTIVITY.MEMBER_REF, memberRef)
                .set(MEMBER_ACTIVITY.KIND, kind)
                .set(MEMBER_ACTIVITY.AMOUNT_CENTS, amountCents)
                .set(MEMBER_ACTIVITY.OCCURRED_AT, occurredAt)
                .set(MEMBER_ACTIVITY.GOAL_ID, goalId)
                .set(MEMBER_ACTIVITY.LOAN_ID, loanId)
                .onConflictDoNothing()
                .execute();
    }

    /** Newest first; the key breaks a tie so the order is the same on every read. */
    public List<MemberActivityItem> newest(UUID memberRef, int limit) {
        return dsl.selectFrom(MEMBER_ACTIVITY)
                .where(MEMBER_ACTIVITY.MEMBER_REF.eq(memberRef))
                .orderBy(MEMBER_ACTIVITY.OCCURRED_AT.desc(), MEMBER_ACTIVITY.ACTIVITY_KEY.desc())
                .limit(limit)
                .fetch(r -> new MemberActivityItem(r.getKind(), r.getAmountCents(), r.getOccurredAt(), r.getGoalId(), r.getLoanId()));
    }
}
