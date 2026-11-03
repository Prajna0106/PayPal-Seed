package com.paypalseed.savings.infrastructure.persistence;

import static com.paypalseed.savings.infrastructure.persistence.jooq.Tables.CREDITED_PAYMENT;
import static com.paypalseed.savings.infrastructure.persistence.jooq.Tables.SAVINGS_GOAL;

import com.paypalseed.savings.application.ports.CreditedPayments;
import com.paypalseed.savings.api.dto.DepositFact;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** {@code savings.credited_payment}: one row per PayPal payment already added to a goal. */
@Repository
public class CreditedPaymentRepository implements CreditedPayments {

    private final DSLContext dsl;

    public CreditedPaymentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean claim(String paypalTxnId, UUID goalId, long amountCents, Source source) {
        return dsl.insertInto(CREDITED_PAYMENT)
            .set(CREDITED_PAYMENT.PAYPAL_TXN_ID, paypalTxnId)
            .set(CREDITED_PAYMENT.GOAL_ID, goalId)
            .set(CREDITED_PAYMENT.AMOUNT_CENTS, amountCents)
            .set(CREDITED_PAYMENT.SOURCE, source.name())
            .onConflictDoNothing()
            .execute() == 1;
    }

    @Override
    public List<Credit> newestOfGoal(UUID goalId, int limit) {
        return dsl.selectFrom(CREDITED_PAYMENT)
            .where(CREDITED_PAYMENT.GOAL_ID.eq(goalId))
            .orderBy(CREDITED_PAYMENT.CREDITED_AT.desc(), CREDITED_PAYMENT.PAYPAL_TXN_ID.desc())
            .limit(limit)
            .fetch(r -> new Credit(Source.valueOf(r.getSource()), r.getAmountCents(), r.getCreditedAt()));
    }

    @Override
    public List<String> membersWithCredits(String afterMemberId, int limit) {
        var query = dsl.selectDistinct(SAVINGS_GOAL.MEMBER_ID)
            .from(SAVINGS_GOAL)
            .where(DSL.exists(dsl.selectOne().from(CREDITED_PAYMENT).where(CREDITED_PAYMENT.GOAL_ID.eq(SAVINGS_GOAL.ID))));
        var ordered = afterMemberId == null ? query : query.and(SAVINGS_GOAL.MEMBER_ID.gt(afterMemberId));
        return ordered.orderBy(SAVINGS_GOAL.MEMBER_ID.asc()).limit(limit).fetch(SAVINGS_GOAL.MEMBER_ID);
    }

    @Override
    public List<DepositFact> creditsOfMember(String memberId) {
        return dsl.select(CREDITED_PAYMENT.PAYPAL_TXN_ID, SAVINGS_GOAL.MEMBER_ID, CREDITED_PAYMENT.GOAL_ID,
                CREDITED_PAYMENT.AMOUNT_CENTS, CREDITED_PAYMENT.CREDITED_AT)
            .from(CREDITED_PAYMENT)
            .join(SAVINGS_GOAL).on(SAVINGS_GOAL.ID.eq(CREDITED_PAYMENT.GOAL_ID))
            .where(SAVINGS_GOAL.MEMBER_ID.eq(memberId))
            .orderBy(CREDITED_PAYMENT.CREDITED_AT.asc(), CREDITED_PAYMENT.PAYPAL_TXN_ID.asc())
            .fetch(r -> new DepositFact(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
    }
}
