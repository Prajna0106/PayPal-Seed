package com.paypalseed.lending.domain.model;

import com.paypalseed.engines.schedule.MoneyEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row of {@code lending.loan_transaction}: money that moved for a loan. Append-only. */
public record LoanTransaction(
        UUID id,
        UUID loanId,
        Type type,
        Source source,
        String paypalTxnId,
        UUID reversesId,
        long amountCents,
        long feeCents,
        String payerRef,
        Instant occurredAt,
        LocalDate effectiveDate,
        UUID journalEntryId,
        String causedBy) {

    public enum Type {
        RECEIPT,
        REVERSAL,
        OVERPAYMENT_REFUND
    }

    public enum Source {
        AUTOPAY,
        AUTOPAY_RETRY,
        CHECKOUT,
        PAYMENT_LINK,
        RECONCILER,
        PAYPAL,
        SEED
    }

    public MoneyEvent toMoneyEvent() {
        return switch (type) {
            case RECEIPT -> new MoneyEvent.Receipt(id, effectiveDate, amountCents);
            case REVERSAL -> new MoneyEvent.Reversal(id, reversesId, effectiveDate, amountCents);
            case OVERPAYMENT_REFUND -> new MoneyEvent.OverpaymentRefund(id, effectiveDate, amountCents);
        };
    }
}
