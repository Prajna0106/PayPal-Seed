package com.paypalseed.payments.infrastructure.persistence;

import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.CAPTURED_PAYMENT;
import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.PAYPAL_ORDER;
import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.REFUND;

import com.paypalseed.payments.infrastructure.persistence.jooq.tables.records.CapturedPaymentRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Orders, captured money and refunds Seed knows about, keyed by PayPal ids. */
@Repository
public class MoneyRegistry {

    public record Captured(String paypalTxnId, UUID loanId, String kind, long amountCents, long refundedCents) {
        public long refundableCents() {
            return amountCents - refundedCents;
        }
    }

    private final DSLContext dsl;

    public MoneyRegistry(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void recordOrder(String orderId, UUID loanId, String purpose, long amountCents, String status) {
        dsl.insertInto(PAYPAL_ORDER)
                .set(PAYPAL_ORDER.PAYPAL_ORDER_ID, orderId)
                .set(PAYPAL_ORDER.LOAN_ID, loanId)
                .set(PAYPAL_ORDER.PURPOSE, purpose)
                .set(PAYPAL_ORDER.AMOUNT_CENTS, amountCents)
                .set(PAYPAL_ORDER.STATUS, status)
                .onConflict(PAYPAL_ORDER.PAYPAL_ORDER_ID)
                .doUpdate().set(PAYPAL_ORDER.STATUS, DSL.excluded(PAYPAL_ORDER.STATUS))
                .execute();
    }

    public void orderStatus(String orderId, String status) {
        dsl.update(PAYPAL_ORDER)
                .set(PAYPAL_ORDER.STATUS, status)
                .where(PAYPAL_ORDER.PAYPAL_ORDER_ID.eq(orderId))
                .execute();
    }

    public Optional<UUID> loanOfOrder(String orderId) {
        return dsl.select(PAYPAL_ORDER.LOAN_ID)
                .from(PAYPAL_ORDER)
                .where(PAYPAL_ORDER.PAYPAL_ORDER_ID.eq(orderId))
                .fetchOptional(PAYPAL_ORDER.LOAN_ID);
    }

    /** Returns false when the transaction was already registered. */
    public boolean recordCaptured(String txnId, UUID loanId, String kind, long amountCents, Instant occurredAt) {
        return dsl.insertInto(CAPTURED_PAYMENT)
                .set(CAPTURED_PAYMENT.PAYPAL_TXN_ID, txnId)
                .set(CAPTURED_PAYMENT.LOAN_ID, loanId)
                .set(CAPTURED_PAYMENT.KIND, kind)
                .set(CAPTURED_PAYMENT.AMOUNT_CENTS, amountCents)
                .set(CAPTURED_PAYMENT.OCCURRED_AT, occurredAt)
                .onConflict(CAPTURED_PAYMENT.PAYPAL_TXN_ID).doNothing()
                .execute() == 1;
    }

    public Optional<Captured> captured(String txnId) {
        return dsl.selectFrom(CAPTURED_PAYMENT)
                .where(CAPTURED_PAYMENT.PAYPAL_TXN_ID.eq(txnId))
                .fetchOptional(MoneyRegistry::mapCaptured);
    }

    /** Captures of a loan with money left to refund, newest first, locked against concurrent refunds. */
    public List<Captured> refundableForUpdate(UUID loanId) {
        return dsl.selectFrom(CAPTURED_PAYMENT)
                .where(CAPTURED_PAYMENT.LOAN_ID.eq(loanId))
                .and(CAPTURED_PAYMENT.REFUNDED_CENTS.lt(CAPTURED_PAYMENT.AMOUNT_CENTS))
                .orderBy(CAPTURED_PAYMENT.OCCURRED_AT.desc(), CAPTURED_PAYMENT.PAYPAL_TXN_ID.desc())
                .forUpdate()
                .fetch(MoneyRegistry::mapCaptured);
    }

    public long refundableCents(UUID loanId) {
        return dsl.select(DSL.coalesce(
                        DSL.sum(CAPTURED_PAYMENT.AMOUNT_CENTS.minus(CAPTURED_PAYMENT.REFUNDED_CENTS)), BigDecimal.ZERO))
                .from(CAPTURED_PAYMENT)
                .where(CAPTURED_PAYMENT.LOAN_ID.eq(loanId))
                .fetchSingle()
                .value1()
                .longValueExact();
    }

    public void addRefunded(String txnId, long cents) {
        dsl.update(CAPTURED_PAYMENT)
                .set(CAPTURED_PAYMENT.REFUNDED_CENTS, CAPTURED_PAYMENT.REFUNDED_CENTS.plus(cents))
                .where(CAPTURED_PAYMENT.PAYPAL_TXN_ID.eq(txnId))
                .execute();
    }

    public boolean recordRefund(String refundId, UUID loanId, String parentTxnId, long amountCents) {
        return dsl.insertInto(REFUND)
                .set(REFUND.PAYPAL_REFUND_ID, refundId)
                .set(REFUND.LOAN_ID, loanId)
                .set(REFUND.PARENT_TXN_ID, parentTxnId)
                .set(REFUND.AMOUNT_CENTS, amountCents)
                .set(REFUND.PURPOSE, "OVERPAYMENT")
                .onConflictDoNothing()
                .execute() == 1;
    }

    public boolean isOurRefund(String refundId) {
        return dsl.fetchExists(REFUND, REFUND.PAYPAL_REFUND_ID.eq(refundId));
    }

    private static Captured mapCaptured(CapturedPaymentRecord r) {
        return new Captured(r.getPaypalTxnId(), r.getLoanId(), r.getKind(), r.getAmountCents(), r.getRefundedCents());
    }
}
