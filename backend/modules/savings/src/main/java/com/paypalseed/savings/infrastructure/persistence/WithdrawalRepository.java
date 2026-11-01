package com.paypalseed.savings.infrastructure.persistence;

import static com.paypalseed.savings.infrastructure.persistence.jooq.Tables.WITHDRAWAL;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.ports.WithdrawalStore;
import com.paypalseed.savings.domain.model.SavingsWallet;
import com.paypalseed.savings.domain.model.Withdrawal;
import com.paypalseed.savings.domain.model.WithdrawalStatus;
import com.paypalseed.savings.infrastructure.persistence.jooq.tables.records.WithdrawalRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code savings.withdrawal}: the saga row around one PayPal payout. */
@Repository
public class WithdrawalRepository implements WithdrawalStore {

    private final DSLContext dsl;

    public WithdrawalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean insert(Withdrawal w) {
        return dsl.insertInto(WITHDRAWAL)
            .set(WITHDRAWAL.ID, w.getId())
            .set(WITHDRAWAL.GOAL_ID, w.getGoalId())
            .set(WITHDRAWAL.MEMBER_ID, w.getMemberId())
            .set(WITHDRAWAL.IDEMPOTENCY_KEY, w.getIdempotencyKey())
            .set(WITHDRAWAL.AMOUNT_CENTS, w.getAmount().cents())
            .set(WITHDRAWAL.WALLET, w.getWallet().name())
            .set(WITHDRAWAL.STATUS, w.getStatus().name())
            .set(WITHDRAWAL.FAILURE_REASON, w.getFailureReason())
            .set(WITHDRAWAL.PAYPAL_TRANSACTION_ID, w.getPaypalTransactionId())
            .set(WITHDRAWAL.CREATED_AT, w.getCreatedAt())
            .set(WITHDRAWAL.UPDATED_AT, w.getUpdatedAt())
            .onConflictDoNothing()
            .execute() == 1;
    }

    @Override
    public Optional<Withdrawal> findByGoalAndKey(UUID goalId, String idempotencyKey) {
        return dsl.selectFrom(WITHDRAWAL)
            .where(WITHDRAWAL.GOAL_ID.eq(goalId).and(WITHDRAWAL.IDEMPOTENCY_KEY.eq(idempotencyKey)))
            .fetchOptional(WithdrawalRepository::map);
    }

    @Override
    public Optional<Withdrawal> findByIdForUpdate(UUID id) {
        return dsl.selectFrom(WITHDRAWAL).where(WITHDRAWAL.ID.eq(id)).forUpdate().fetchOptional(WithdrawalRepository::map);
    }

    @Override
    public List<Withdrawal> findByGoal(UUID goalId) {
        return dsl.selectFrom(WITHDRAWAL).where(WITHDRAWAL.GOAL_ID.eq(goalId)).orderBy(WITHDRAWAL.CREATED_AT.desc())
            .fetch(WithdrawalRepository::map);
    }

    @Override
    public void update(Withdrawal w) {
        dsl.update(WITHDRAWAL)
            .set(WITHDRAWAL.STATUS, w.getStatus().name())
            .set(WITHDRAWAL.FAILURE_REASON, w.getFailureReason())
            .set(WITHDRAWAL.PAYPAL_TRANSACTION_ID, w.getPaypalTransactionId())
            .set(WITHDRAWAL.UPDATED_AT, w.getUpdatedAt())
            .where(WITHDRAWAL.ID.eq(w.getId()))
            .execute();
    }

    private static Withdrawal map(WithdrawalRecord r) {
        return new Withdrawal(r.getId(), r.getGoalId(), r.getMemberId(), r.getIdempotencyKey(), Money.ofCents(r.getAmountCents()),
            SavingsWallet.valueOf(r.getWallet()), WithdrawalStatus.valueOf(r.getStatus()), r.getFailureReason(),
            r.getPaypalTransactionId(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
