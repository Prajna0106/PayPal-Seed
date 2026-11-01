package com.paypalseed.payments.infrastructure.persistence;

import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.SAVINGS_SUBSCRIPTION;

import com.paypalseed.payments.infrastructure.persistence.jooq.tables.records.SavingsSubscriptionRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code payments.savings_subscription}: monthly deposits into savings goals. */
@Repository
public class SavingsSubscriptionRepository {

    public record SavingsSubscription(UUID id, UUID goalId, UUID memberId, String requestId, long monthlyCents,
                                      String subscriptionId, String approveUrl, String status) {
    }

    private final DSLContext dsl;

    public SavingsSubscriptionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<SavingsSubscription> byGoalAndRequest(UUID goalId, String requestId) {
        return dsl.selectFrom(SAVINGS_SUBSCRIPTION)
                .where(SAVINGS_SUBSCRIPTION.GOAL_ID.eq(goalId)).and(SAVINGS_SUBSCRIPTION.REQUEST_ID.eq(requestId))
                .fetchOptional(SavingsSubscriptionRepository::map);
    }

    public Optional<SavingsSubscription> bySubscription(String subscriptionId) {
        return dsl.selectFrom(SAVINGS_SUBSCRIPTION)
                .where(SAVINGS_SUBSCRIPTION.PAYPAL_SUBSCRIPTION_ID.eq(subscriptionId))
                .fetchOptional(SavingsSubscriptionRepository::map);
    }

    public Optional<SavingsSubscription> latestByGoal(UUID goalId) {
        return dsl.selectFrom(SAVINGS_SUBSCRIPTION)
                .where(SAVINGS_SUBSCRIPTION.GOAL_ID.eq(goalId))
                .orderBy(SAVINGS_SUBSCRIPTION.CREATED_AT.desc())
                .limit(1)
                .fetchOptional(SavingsSubscriptionRepository::map);
    }

    /** The newest subscription of the goal, locked: a goal changes its subscription one request at a time. */
    public Optional<SavingsSubscription> latestByGoalForUpdate(UUID goalId) {
        return dsl.selectFrom(SAVINGS_SUBSCRIPTION)
                .where(SAVINGS_SUBSCRIPTION.GOAL_ID.eq(goalId))
                .orderBy(SAVINGS_SUBSCRIPTION.CREATED_AT.desc())
                .limit(1)
                .forUpdate()
                .fetchOptional(SavingsSubscriptionRepository::map);
    }

    public void monthlyCents(String subscriptionId, long monthlyCents) {
        dsl.update(SAVINGS_SUBSCRIPTION)
                .set(SAVINGS_SUBSCRIPTION.MONTHLY_CENTS, monthlyCents)
                .set(SAVINGS_SUBSCRIPTION.UPDATED_AT, PgSql.now())
                .where(SAVINGS_SUBSCRIPTION.PAYPAL_SUBSCRIPTION_ID.eq(subscriptionId))
                .execute();
    }

    public boolean isSavingsSubscription(String subscriptionId) {
        return dsl.fetchExists(SAVINGS_SUBSCRIPTION, SAVINGS_SUBSCRIPTION.PAYPAL_SUBSCRIPTION_ID.eq(subscriptionId));
    }

    /** False when the (goal, request) already exists, which is a concurrent create of the same subscription. */
    public boolean insert(SavingsSubscription s) {
        return dsl.insertInto(SAVINGS_SUBSCRIPTION)
                .set(SAVINGS_SUBSCRIPTION.ID, s.id())
                .set(SAVINGS_SUBSCRIPTION.GOAL_ID, s.goalId())
                .set(SAVINGS_SUBSCRIPTION.MEMBER_ID, s.memberId())
                .set(SAVINGS_SUBSCRIPTION.REQUEST_ID, s.requestId())
                .set(SAVINGS_SUBSCRIPTION.MONTHLY_CENTS, s.monthlyCents())
                .set(SAVINGS_SUBSCRIPTION.PAYPAL_SUBSCRIPTION_ID, s.subscriptionId())
                .set(SAVINGS_SUBSCRIPTION.APPROVE_URL, s.approveUrl())
                .set(SAVINGS_SUBSCRIPTION.STATUS, s.status())
                .onConflictDoNothing()
                .execute() == 1;
    }

    public void status(String subscriptionId, String status) {
        dsl.update(SAVINGS_SUBSCRIPTION)
                .set(SAVINGS_SUBSCRIPTION.STATUS, status)
                .set(SAVINGS_SUBSCRIPTION.UPDATED_AT, PgSql.now())
                .where(SAVINGS_SUBSCRIPTION.PAYPAL_SUBSCRIPTION_ID.eq(subscriptionId))
                .execute();
    }

    private static SavingsSubscription map(SavingsSubscriptionRecord r) {
        return new SavingsSubscription(r.getId(), r.getGoalId(), r.getMemberId(), r.getRequestId(), r.getMonthlyCents(),
                r.getPaypalSubscriptionId(), r.getApproveUrl(), r.getStatus());
    }
}
