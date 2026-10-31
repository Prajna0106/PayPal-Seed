package com.paypalseed.support;

import com.paypalseed.platform.kernel.Money;
import java.time.Instant;
import java.util.UUID;

/** PayPal webhook bodies shaped like the ones the sandbox sends. */
public final class PayPalWebhooks {

    private PayPalWebhooks() {
    }

    private static String envelope(String eventId, String type, String resource) {
        return """
                {"id":"%s","event_version":"1.0","create_time":"%s","event_type":"%s","resource":%s}
                """.formatted(eventId, Instant.now(), type, resource);
    }

    public static String newEventId() {
        return "WH-" + UUID.randomUUID();
    }

    public static String subscription(String eventId, String type, String subscriptionId, String status, long quantity,
                                      Instant nextBilling) {
        return envelope(eventId, type, """
                {"id":"%s","status":"%s","quantity":"%d","billing_info":{"next_billing_time":"%s"}}
                """.formatted(subscriptionId, status, quantity, nextBilling));
    }

    public static String saleCompleted(String eventId, String subscriptionId, String saleId, long cents, long feeCents) {
        return envelope(eventId, "PAYMENT.SALE.COMPLETED", """
                {"id":"%s","state":"completed","billing_agreement_id":"%s","amount":{"total":"%s","currency":"USD"},
                 "transaction_fee":{"value":"%s","currency":"USD"},"create_time":"%s"}
                """.formatted(saleId, subscriptionId, Money.ofCents(cents).toDecimalString(),
                Money.ofCents(feeCents).toDecimalString(), Instant.now()));
    }

    public static String paymentFailed(String eventId, String subscriptionId) {
        return envelope(eventId, "BILLING.SUBSCRIPTION.PAYMENT.FAILED", """
                {"id":"%s","status":"ACTIVE"}
                """.formatted(subscriptionId));
    }

    public static String saleRefunded(String eventId, String refundId, String saleId, long cents) {
        return envelope(eventId, "PAYMENT.SALE.REFUNDED", """
                {"id":"%s","sale_id":"%s","amount":{"total":"%s","currency":"USD"},"create_time":"%s"}
                """.formatted(refundId, saleId, Money.ofCents(cents).toDecimalString(), Instant.now()));
    }

    public static String captureRefunded(String eventId, String refundId, String captureId, long cents) {
        return envelope(eventId, "PAYMENT.CAPTURE.REFUNDED", """
                {"id":"%s","status":"COMPLETED","amount":{"value":"%s","currency_code":"USD"},"create_time":"%s",
                 "links":[{"rel":"up","href":"https://api-m.sandbox.paypal.com/v2/payments/captures/%s"}]}
                """.formatted(refundId, Money.ofCents(cents).toDecimalString(), Instant.now(), captureId));
    }

    public static String captureCompleted(String eventId, String captureId, UUID loanId, long cents) {
        return envelope(eventId, "PAYMENT.CAPTURE.COMPLETED", """
                {"id":"%s","status":"COMPLETED","custom_id":"loan:%s:CATCH_UP","amount":{"value":"%s","currency_code":"USD"},
                 "seller_receivable_breakdown":{"paypal_fee":{"value":"0.00","currency_code":"USD"}},"create_time":"%s"}
                """.formatted(captureId, loanId, Money.ofCents(cents).toDecimalString(), Instant.now()));
    }
}
