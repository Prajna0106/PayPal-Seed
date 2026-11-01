package com.paypalseed.payments.api.events;

import com.paypalseed.payments.api.dto.PaymentsDtos.FundingSource;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import java.time.Instant;
import java.util.UUID;

/**
 * Normalised money facts from PayPal (04-paypal-integration Â§7). Every PayPal webhook, API response
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

    /** The money reached the recipient. Published once per payout, whichever of API or webhook reports it first. */
    public record PayoutSucceeded(UUID eventId, UUID payoutId, String referenceId, long amountCents, PayoutWallet wallet,
                                  String paypalTransactionId, long feeCents, Instant occurredAt, String causedBy) {
    }

    /**
     * The money did not reach the recipient. {@code terminal} is false for UNCLAIMED: PayPal holds the money
     * and a later {@link PayoutSucceeded} or {@link PayoutReturned} follows.
     */
    public record PayoutFailed(UUID eventId, UUID payoutId, String referenceId, long amountCents, String reasonCode,
                               boolean terminal, Instant occurredAt, String causedBy) {
    }

    /** The recipient did not claim the money, or PayPal took it back; it is in the program account again. */
    public record PayoutReturned(UUID eventId, UUID payoutId, String referenceId, long amountCents, String reasonCode,
                                 Instant occurredAt, String causedBy) {
    }

    /** A one-off top-up into a savings goal was captured (PayPal or Venmo). Published once per capture. */
    public record SavingsTopUpCaptured(UUID eventId, UUID memberId, UUID goalId, String paypalCaptureId, long amountCents,
                                       FundingSource funding, Instant occurredAt, String causedBy) {
    }

    /**
     * A deposit was billed on a savings subscription. {@code paypalTxnId} is the money identity the savings module
     * dedupes on; the goal is named by the recorded subscription, never guessed from the payload.
     */
    public record SavingsDepositReceived(UUID eventId, UUID goalId, UUID memberId, String subscriptionId, String paypalTxnId,
                                         long amountCents, long feeCents, Instant occurredAt, String causedBy) {
    }

    public enum SavingsSubscriptionState {
        ACTIVE,
        SUSPENDED,
        CANCELLED,
        EXPIRED
    }

    /**
     * PayPal says a savings subscription is now {@code state}: the member approved it, or it was suspended,
     * cancelled or expired (by the member, by PayPal or by Seed). Published for every webhook that reports a
     * state; the savings module ignores a state the goal is already in.
     */
    public record SavingsSubscriptionStateChanged(UUID eventId, UUID goalId, UUID memberId, String subscriptionId,
                                                  SavingsSubscriptionState state, Instant occurredAt, String causedBy) {
    }

    /**
     * A donation to the reserve fund was captured (a capture whose {@code custom_id} starts with
     * {@code reserve-donation:}). No donor identity travels with it.
     */
    public record DonationCaptured(UUID eventId, String paypalTxnId, String customId, long grossCents, long feeCents,
                                   Instant occurredAt) {
    }
}