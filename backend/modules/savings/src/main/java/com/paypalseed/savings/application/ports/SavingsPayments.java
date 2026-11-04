package com.paypalseed.savings.application.ports;

import com.paypalseed.savings.domain.model.SavingsWallet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything savings asks of PayPal, through the payments module. Savings never learns how: it asks, and
 * the outcome comes back as events. Every call that writes carries a request id, so a retry after a
 * timeout or a rolled-back transaction is the same request at PayPal.
 */
public interface SavingsPayments {

    record Subscription(String subscriptionId, String status, Optional<String> approvalUrl) {
    }

    record Order(String orderId, String status, Optional<String> approvalUrl) {
    }

    record OrderCapture(String orderId, String status, long amountCents) {
    }

    enum PayoutState {
        /** The money reached the recipient. */
        SUCCEEDED,
        /** PayPal has it and will report the end by webhook. */
        PENDING,
        /** PayPal refused it or could not deliver it; the reference is spent. */
        FAILED
    }

    record Payout(PayoutState state, String reasonCode, String transactionId) {
    }

    Subscription startSubscription(UUID memberId, UUID goalId, long monthlyCents, String requestId, String returnUrl,
                                   String cancelUrl);

    Optional<Subscription> subscriptionOf(UUID goalId);

    enum SubscriptionState {
        ACTIVE,
        SUSPENDED,
        CANCELLED,
        EXPIRED
    }

    /** A state PayPal reports for the subscription, and when it became so. */
    record StateReport(SubscriptionState state, Instant at) {
    }

    /** A completed sale of the subscription: a deposit, with the PayPal transaction id it is credited under. */
    record SaleReport(String transactionId, long amountCents, Instant at) {
    }

    /** What PayPal says now about the goal's subscription; {@code state} is empty while PayPal reports none the goal needs (not approved yet). */
    record SubscriptionReport(String subscriptionId, Optional<StateReport> state, List<SaleReport> sales) {
    }

    /**
     * Reads the goal's subscription and recent sales from PayPal, for a webhook that did not come (ADR 0028). Empty when the
     * goal has no subscription at PayPal. Throws when PayPal cannot be reached.
     */
    default Optional<SubscriptionReport> readSubscription(UUID goalId) {
        return Optional.empty();
    }

    /** When PayPal will bill the goal's subscription next, as it last reported; empty when nothing is scheduled. */
    default Optional<Instant> nextBillingTime(UUID goalId) {
        return Optional.empty();
    }

    void suspend(UUID goalId, String requestId, String reason);

    /** Only for a subscription that is still there; check {@link #subscriptionOf} first, a cancelled one cannot come back. */
    void activate(UUID goalId, String requestId, String reason);

    /** The consent URL when PayPal asks the member to agree to the new amount. */
    Optional<String> revise(UUID goalId, long monthlyCents, String requestId);

    void cancel(UUID goalId, String requestId, String reason);

    Order startTopUp(UUID memberId, UUID goalId, long amountCents, SavingsWallet funding, String idempotencyKey);

    OrderCapture captureTopUp(UUID goalId, String orderId);

    /** Where a payout stands after PayPal was asked, in the words the payout events use (ADR 0029). */
    enum PayoutEnd {
        PENDING,
        /** The recipient has no account yet; PayPal holds the money and a success or a return follows. */
        UNCLAIMED,
        SUCCEEDED,
        FAILED,
        RETURNED
    }

    record PayoutReport(PayoutEnd end, String reasonCode, String transactionId) {
    }

    /**
     * Asks PayPal how the payout stands, for a webhook that did not come (ADR 0029), and returns what is stored afterwards;
     * empty when there is no such payout. Throws when PayPal cannot be reached.
     */
    default Optional<PayoutReport> syncPayout(String referenceId) {
        return Optional.empty();
    }

    /** Sends the money out to {@code memberId}. Idempotent on {@code referenceId}. */
    Payout sendPayout(String referenceId, UUID memberId, long amountCents, PayoutRecipients.Recipient recipient, SavingsWallet wallet,
                      String note);
}
