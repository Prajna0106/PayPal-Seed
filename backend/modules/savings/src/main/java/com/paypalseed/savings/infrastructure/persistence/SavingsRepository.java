package com.paypalseed.savings.infrastructure.persistence;

import static com.paypalseed.savings.infrastructure.persistence.jooq.Tables.SAVINGS_GOAL;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.infrastructure.persistence.jooq.tables.records.SavingsGoalRecord;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class SavingsRepository implements SavingsGoalRepository {

    private final DSLContext dsl;
    private final Map<UUID, SavingsGoal> storage = new ConcurrentHashMap<>();

    public SavingsRepository() {
        this.dsl = null;
    }

    @Autowired
    public SavingsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public SavingsGoal save(SavingsGoal goal) {
        Objects.requireNonNull(goal, "Goal cannot be null");
        if (dsl == null) {
            storage.put(goal.getId(), goal);
            return goal;
        }

        dsl.insertInto(SAVINGS_GOAL)
            .set(SAVINGS_GOAL.ID, goal.getId())
            .set(SAVINGS_GOAL.MEMBER_ID, goal.getMemberId())
            .set(SAVINGS_GOAL.NAME, goal.getName())
            .set(SAVINGS_GOAL.CURRENCY, goal.getMonthlyAmount().currency())
            .set(SAVINGS_GOAL.TARGET_AMOUNT_CENTS, goal.getTargetAmount().cents())
            .set(SAVINGS_GOAL.MONTHLY_AMOUNT_CENTS, goal.getMonthlyAmount().cents())
            .set(SAVINGS_GOAL.TARGET_DATE, goal.getTargetDate())
            .set(SAVINGS_GOAL.PAYPAL_SUBSCRIPTION_ID, goal.getPayPalSubscriptionId())
            .set(SAVINGS_GOAL.STATUS, goal.getStatus().name())
            .set(SAVINGS_GOAL.CURRENT_BALANCE_CENTS, goal.getCurrentBalance().cents())
            .set(SAVINGS_GOAL.DEPOSIT_COUNT, goal.getDepositCount())
            .set(SAVINGS_GOAL.CREATED_AT, goal.getCreatedAt())
            .set(SAVINGS_GOAL.UPDATED_AT, goal.getUpdatedAt())
            .onConflict(SAVINGS_GOAL.ID).doUpdate()
            .set(SAVINGS_GOAL.NAME, DSL.excluded(SAVINGS_GOAL.NAME))
            .set(SAVINGS_GOAL.TARGET_AMOUNT_CENTS, DSL.excluded(SAVINGS_GOAL.TARGET_AMOUNT_CENTS))
            .set(SAVINGS_GOAL.MONTHLY_AMOUNT_CENTS, DSL.excluded(SAVINGS_GOAL.MONTHLY_AMOUNT_CENTS))
            .set(SAVINGS_GOAL.TARGET_DATE, DSL.excluded(SAVINGS_GOAL.TARGET_DATE))
            .set(SAVINGS_GOAL.PAYPAL_SUBSCRIPTION_ID, DSL.excluded(SAVINGS_GOAL.PAYPAL_SUBSCRIPTION_ID))
            .set(SAVINGS_GOAL.STATUS, DSL.excluded(SAVINGS_GOAL.STATUS))
            .set(SAVINGS_GOAL.CURRENT_BALANCE_CENTS, DSL.excluded(SAVINGS_GOAL.CURRENT_BALANCE_CENTS))
            .set(SAVINGS_GOAL.DEPOSIT_COUNT, DSL.excluded(SAVINGS_GOAL.DEPOSIT_COUNT))
            .set(SAVINGS_GOAL.UPDATED_AT, DSL.excluded(SAVINGS_GOAL.UPDATED_AT))
            .execute();

        return goal;
    }

    @Override
    public Optional<SavingsGoal> findById(UUID id) {
        if (dsl == null) {
            return Optional.ofNullable(storage.get(id));
        }
        return dsl.selectFrom(SAVINGS_GOAL)
            .where(SAVINGS_GOAL.ID.eq(id))
            .fetchOptional(SavingsRepository::map);
    }

    @Override
    public List<SavingsGoal> findByMemberId(String memberId) {
        if (dsl == null) {
            return storage.values().stream()
                .filter(g -> g.getMemberId().equalsIgnoreCase(memberId))
                .sorted(Comparator.comparing(SavingsGoal::getCreatedAt))
                .toList();
        }
        return dsl.selectFrom(SAVINGS_GOAL)
            .where(SAVINGS_GOAL.MEMBER_ID.equalIgnoreCase(memberId))
            .orderBy(SAVINGS_GOAL.CREATED_AT.asc())
            .fetch(SavingsRepository::map);
    }

    @Override
    public Optional<SavingsGoal> findByPayPalSubscriptionId(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.trim().isEmpty()) {
            return Optional.empty();
        }
        if (dsl == null) {
            return storage.values().stream()
                .filter(g -> subscriptionId.equalsIgnoreCase(g.getPayPalSubscriptionId()))
                .findFirst();
        }
        return dsl.selectFrom(SAVINGS_GOAL)
            .where(SAVINGS_GOAL.PAYPAL_SUBSCRIPTION_ID.equalIgnoreCase(subscriptionId.trim()))
            .orderBy(SAVINGS_GOAL.CREATED_AT.desc())
            .limit(1)
            .fetchOptional(SavingsRepository::map);
    }

    @Override
    public long countActiveByMemberId(String memberId) {
        if (dsl == null) {
            return storage.values().stream()
                .filter(g -> g.getMemberId().equalsIgnoreCase(memberId) && g.getStatus() == GoalStatus.ACTIVE)
                .count();
        }
        Integer count = dsl.selectCount()
            .from(SAVINGS_GOAL)
            .where(SAVINGS_GOAL.MEMBER_ID.equalIgnoreCase(memberId)
                .and(SAVINGS_GOAL.STATUS.eq(GoalStatus.ACTIVE.name())))
            .fetchOne(0, Integer.class);
        return count != null ? count.longValue() : 0L;
    }

    public void clear() {
        storage.clear();
        if (dsl != null) {
            dsl.deleteFrom(SAVINGS_GOAL).execute();
        }
    }

    private static SavingsGoal map(SavingsGoalRecord r) {
        return new SavingsGoal(
            r.getId(),
            r.getMemberId(),
            r.getName(),
            Money.ofCents(r.getTargetAmountCents()),
            Money.ofCents(r.getMonthlyAmountCents()),
            r.getTargetDate(),
            r.getPaypalSubscriptionId(),
            GoalStatus.valueOf(r.getStatus()),
            Money.ofCents(r.getCurrentBalanceCents()),
            r.getDepositCount(),
            r.getCreatedAt(),
            r.getUpdatedAt()
        );
    }
}
