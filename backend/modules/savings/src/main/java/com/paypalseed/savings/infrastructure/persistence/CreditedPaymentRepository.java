package com.paypalseed.savings.infrastructure.persistence;

import static com.paypalseed.savings.infrastructure.persistence.jooq.Tables.CREDITED_PAYMENT;

import com.paypalseed.savings.application.ports.CreditedPayments;
import java.util.UUID;
import org.jooq.DSLContext;
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
}
