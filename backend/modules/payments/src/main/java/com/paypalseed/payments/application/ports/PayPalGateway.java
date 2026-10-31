package com.paypalseed.payments.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PayPal REST operations Seed uses for repayments (04-paypal-integration §2–§7). The production
 * adapter calls PayPal; tests plug in a stateful simulator. Every write takes a {@code requestId},
 * sent as {@code PayPal-Request-Id}, so a replay after a timeout returns the original result.
 */
public interface PayPalGateway {

    record Subscription(String id, String status, long quantity, Optional<Instant> nextBillingTime,
                        Optional<String> approveUrl, Optional<String> customId) {
    }

    record CreateSubscription(long quantity, String customId, Instant startTime, String returnUrl, String cancelUrl) {
    }

    /** {@code approveUrl} is present when PayPal needs the payer to consent to the change. */
    record Revision(Optional<String> approveUrl) {
    }

    record Order(String id, String status, Optional<String> approveUrl) {
    }

    record CreateOrder(long amountCents, String customId, String invoiceId, String description) {
    }

    record Capture(String id, String status, long amountCents, long feeCents, Instant createTime) {
    }

    record SubscriptionTransaction(String id, String status, long grossCents, long feeCents, Instant time) {
    }

    record Refund(String id, String status, long amountCents) {
    }

    Subscription createSubscription(CreateSubscription request, String requestId);

    Subscription getSubscription(String subscriptionId);

    Revision reviseQuantity(String subscriptionId, long quantity, String requestId);

    void suspend(String subscriptionId, String reason, String requestId);

    void activate(String subscriptionId, String reason, String requestId);

    void cancel(String subscriptionId, String reason, String requestId);

    /** Captures an outstanding balance on a subscription (the single retry). */
    Capture captureOutstanding(String subscriptionId, long amountCents, String note, String requestId);

    List<SubscriptionTransaction> subscriptionTransactions(String subscriptionId, Instant from, Instant to);

    Order createOrder(CreateOrder request, String requestId);

    Capture captureOrder(String orderId, String requestId);

    Refund refundCapture(String captureId, long amountCents, String invoiceId, String requestId);

    Refund refundSale(String saleId, long amountCents, String requestId);

    /** PayPal's verify-webhook-signature check over the transmission headers and raw body. */
    boolean verifyWebhook(Map<String, String> transmissionHeaders, String rawBody);
}
