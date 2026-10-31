package com.paypalseed.payments.api.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Normalised money facts from PayPal (04-paypal-integration §7). Every PayPal webhook, API response
 * or reconciler find for a loan becomes exactly one of these, so lending has one funnel to handle.
 * {@code paypalTxnId} is the money identity consumers dedupe on.
 */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    public enum Source {
        AUTOPAY,
        AUTOPAY_RETRY,
        CHECKOUT,
        PAYMENT_LINK,
        RECONCILER
    }

    public enum ReversalKind {
        /** Refund the member asked PayPal for, or Seed issued outside the overpayment flow. */
        REFUND,
        /** PayPal reversed the payment (for example a funding failure). */
        REVERSAL,
        /** A dispute was lost. */
        CHARGEBACK
    }

    public record PaymentCaptured(UUID eventId, UUID loanId, String paypalTxnId, Source source, long grossCents,
                                  long feeCents, Instant occurredAt, String payerRef, String causedBy) {
    }

    public record PaymentPending(UUID eventId, UUID loanId, String paypalTxnId, long amountCents, Instant occurredAt) {
    }

    /** {@code paypalTxnId} is set when the failure concerned a known pending transaction. */
    public record PaymentFailed(UUID eventId, UUID loanId, Source source, String paypalTxnId, String reason,
                                Instant occurredAt, String causedBy) {
    }

    public record PaymentReversed(UUID eventId, UUID loanId, String paypalTxnId, String originalTxnId, ReversalKind kind,
                                  long amountCents, Instant occurredAt, String causedBy) {
    }

    /** A refund Seed initiated to return overpaid money; completed at PayPal. */
    public record OverpaymentRefundCompleted(UUID eventId, UUID loanId, String paypalRefundId, String originalTxnId,
                                             long amountCents, Instant occurredAt) {
    }

    public record MandateActivated(UUID eventId, UUID loanId, UUID mandateId, String subscriptionId, Instant occurredAt) {
    }

    public enum MandateEndCause {
        SEED,
        MEMBER,
        PAYPAL
    }

    public record MandateEnded(UUID eventId, UUID loanId, UUID mandateId, MandateEndCause cause, Instant occurredAt) {
    }

    public record MandateDriftDetected(UUID eventId, UUID loanId, UUID memberId, UUID mandateId, String detail, Instant occurredAt) {
    }
}
