package com.paypalseed.savings.application.ports;

import com.paypalseed.savings.domain.model.SavingsWallet;
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

    void suspend(UUID goalId, String requestId, String reason);

    /** Only for a subscription that is still there; check {@link #subscriptionOf} first, a cancelled one cannot come back. */
    void activate(UUID goalId, String requestId, String reason);

    /** The consent URL when PayPal asks the member to agree to the new amount. */
    Optional<String> revise(UUID goalId, long monthlyCents, String requestId);

    void cancel(UUID goalId, String requestId, String reason);

    Order startTopUp(UUID memberId, UUID goalId, long amountCents, SavingsWallet funding, String idempotencyKey);

    OrderCapture captureTopUp(UUID goalId, String orderId);

    /** Sends the money out. Idempotent on {@code referenceId}. */
    Payout sendPayout(String referenceId, long amountCents, PayoutRecipients.Recipient recipient, SavingsWallet wallet, String note);
}
