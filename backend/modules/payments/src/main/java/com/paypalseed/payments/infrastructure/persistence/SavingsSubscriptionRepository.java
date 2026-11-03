package com.paypalseed.payments.infrastructure.persistence;

import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.SAVINGS_SUBSCRIPTION;

import com.paypalseed.payments.infrastructure.persistence.jooq.tables.records.SavingsSubscriptionRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code payments.savings_subscription}: monthly deposits into savings goals. */
@Repository
public class SavingsSubscriptionRepository {

    /** {@code nextBillingTime} is PayPal's next charge time as last reported; null while none is scheduled. */
    public record SavingsSubscription(UUID id, UUID goalId, UUID memberId, String requestId, long monthlyCents,
                                      String subscriptionId, String approveUrl, String status, Instant nextBillingTime) {

        public SavingsSubscription(UUID id, UUID goalId, UUID memberId, String requestId, long monthlyCents,
                                   String subscriptionId, String approveUrl, String status) {
            this(id, goalId, memberId, requestId, monthlyCents, subscriptionId, approveUrl, status, null);
        }
    }

    /** PayPal cannot bring these back, so no later-arriving report may replace them. */
    private static final List<String> ENDED = List.of("CANCELLED", "EXPIRED");
    private static final String ACTIVE = "ACTIVE";

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

    /**
     * Seed's own change (it just asked PayPal for it): applied as it stands, and dated now. A subscription that is no
     * longer active has no next charge, so the stored time is cleared.
     */
    public void status(String subscriptionId, String status) {
        var update = dsl.update(SAVINGS_SUBSCRIPTION)
                .set(SAVINGS_SUBSCRIPTION.STATUS, status)
                .set(SAVINGS_SUBSCRIPTION.STATUS_AT, PgSql.now())
                .set(SAVINGS_SUBSCRIPTION.UPDATED_AT, PgSql.now());
        if (!ACTIVE.equals(status)) {
            update = update.set(SAVINGS_SUBSCRIPTION.NEXT_BILLING_TIME, (Instant) null);
        }
        update.where(SAVINGS_SUBSCRIPTION.PAYPAL_SUBSCRIPTION_ID.eq(subscriptionId)).execute();
    }

    /**
     * Stores PayPal's next charge time ({@code null} clears it). An ended subscription keeps no time: PayPal cannot bring
     * it back, so a late report must not give it one.
     */
    public void nextBillingTime(String subscriptionId, Instant nextBillingTime) {
        dsl.update(SAVINGS_SUBSCRIPTION)
                .set(SAVINGS_SUBSCRIPTION.NEXT_BILLING_TIME, nextBillingTime)
                .set(SAVINGS_SUBSCRIPTION.UPDATED_AT, PgSql.now())
                .where(SAVINGS_SUBSCRIPTION.PAYPAL_SUBSCRIPTION_ID.eq(subscriptionId))
                .and(SAVINGS_SUBSCRIPTION.STATUS.notIn(ENDED))
                .execute();
    }

    /**
     * What PayPal reported at {@code reportedAt}. Reports arrive out of order, so one older than the stored status is
     * ignored, and a status that ended the subscription (CANCELLED, EXPIRED) is never replaced by one that did not.
     * Returns whether the report was applied.
     */
    public boolean status(String subscriptionId, String status, Instant reportedAt) {
        Condition current = SAVINGS_SUBSCRIPTION.PAYPAL_SUBSCRIPTION_ID.eq(subscriptionId)
                .and(SAVINGS_SUBSCRIPTION.STATUS_AT.isNull().or(SAVINGS_SUBSCRIPTION.STATUS_AT.le(reportedAt)));
        if (!ENDED.contains(status)) {
            current = current.and(SAVINGS_SUBSCRIPTION.STATUS.notIn(ENDED));
        }
        return dsl.update(SAVINGS_SUBSCRIPTION)
                .set(SAVINGS_SUBSCRIPTION.STATUS, status)
                .set(SAVINGS_SUBSCRIPTION.STATUS_AT, reportedAt)
                .set(SAVINGS_SUBSCRIPTION.UPDATED_AT, PgSql.now())
                .where(current)
                .execute() == 1;
    }

    /** When the stored status was last set or reported; empty while no report or change of Seed's own has been stored. */
    public Optional<Instant> statusAt(String subscriptionId) {
        return Optional.ofNullable(dsl.select(SAVINGS_SUBSCRIPTION.STATUS_AT).from(SAVINGS_SUBSCRIPTION)
                .where(SAVINGS_SUBSCRIPTION.PAYPAL_SUBSCRIPTION_ID.eq(subscriptionId))
                .fetchOne(SAVINGS_SUBSCRIPTION.STATUS_AT));
    }

    private static SavingsSubscription map(SavingsSubscriptionRecord r) {
        return new SavingsSubscription(r.getId(), r.getGoalId(), r.getMemberId(), r.getRequestId(), r.getMonthlyCents(),
                r.getPaypalSubscriptionId(), r.getApproveUrl(), r.getStatus(), r.getNextBillingTime());
    }
}
