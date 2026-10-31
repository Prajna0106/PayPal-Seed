package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Money that moved for a loan, as the allocation fold sees it. Ids are time-ordered UUIDs, so
 * (effectiveDate, id) is a total, stable processing order.
 */
public sealed interface MoneyEvent permits MoneyEvent.Receipt, MoneyEvent.Reversal, MoneyEvent.OverpaymentRefund {

    UUID id();

    LocalDate effectiveDate();

    long amountCents();

    /** Money received from the member (autopay, checkout, payment link, reconciler). */
    record Receipt(UUID id, LocalDate effectiveDate, long amountCents) implements MoneyEvent {
        public Receipt {
            requireValid(id, effectiveDate, amountCents);
        }
    }

    /**
     * A receipt clawed back (refund the member asked PayPal for, reversal, lost dispute). It reduces
     * the receipt's net amount at the receipt's original position, as if that much never arrived.
     */
    record Reversal(UUID id, UUID receiptId, LocalDate effectiveDate, long amountCents) implements MoneyEvent {
        public Reversal {
            requireValid(id, effectiveDate, amountCents);
            Objects.requireNonNull(receiptId, "receiptId");
        }
    }

    /** Overpaid money Seed returned to the member (Fineract's "credit balance refund"). */
    record OverpaymentRefund(UUID id, LocalDate effectiveDate, long amountCents) implements MoneyEvent {
        public OverpaymentRefund {
            requireValid(id, effectiveDate, amountCents);
        }
    }

    private static void requireValid(UUID id, LocalDate date, long amountCents) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "effectiveDate");
        if (amountCents <= 0) {
            throw new IllegalArgumentException("money event amount must be positive");
        }
    }
}
