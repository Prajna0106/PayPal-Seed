package com.paypalseed.payments.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.application.PayPalWebhookVerifier;
import com.paypalseed.payments.application.WebhookInbox;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.SavingsFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments/webhooks")
public class PayPalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookController.class);

    private final WebhookInbox webhookInbox;
    private final PayPalWebhookVerifier webhookVerifier;
    private final SavingsFacade savingsFacade;
    private final ObjectMapper objectMapper;

    public PayPalWebhookController(
        WebhookInbox webhookInbox,
        PayPalWebhookVerifier webhookVerifier,
        SavingsFacade savingsFacade,
        ObjectMapper objectMapper
    ) {
        this.webhookInbox = webhookInbox;
        this.webhookVerifier = webhookVerifier;
        this.savingsFacade = savingsFacade;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> handleWebhook(
        @RequestHeader Map<String, String> headers,
        @RequestBody String rawPayload
    ) {
        log.info("[PAYPAL WEBHOOK] Received webhook event notification");

        if (!webhookVerifier.verifySignature(headers, rawPayload)) {
            log.warn("[PAYPAL WEBHOOK] Signature verification failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid webhook signature"));
        }

        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String eventId = root.path("id").asText();
            String eventType = root.path("event_type").asText();

            if (eventId == null || eventId.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing event id"));
            }

            // Inbox Deduplication (Idempotency)
            if (webhookInbox.isProcessed(eventId)) {
                log.info("[PAYPAL WEBHOOK] Event {} already processed. Skipping.", eventId);
                return ResponseEntity.ok(Map.of(
                    "status", "DUPLICATE_IGNORED",
                    "eventId", eventId
                ));
            }

            log.info("[PAYPAL WEBHOOK] Ingesting event: {} (type: {})", eventId, eventType);

            switch (eventType) {
                case "PAYMENT.SALE.COMPLETED" -> handlePaymentSaleCompleted(root.path("resource"));
                case "BILLING.SUBSCRIPTION.CANCELLED" -> handleSubscriptionCancelled(root.path("resource"));
                case "BILLING.SUBSCRIPTION.PAYMENT.FAILED" -> handleSubscriptionPaymentFailed(root.path("resource"));
                default -> log.info("[PAYPAL WEBHOOK] Unhandled event type: {}", eventType);
            }

            // Mark event as processed in inbox
            webhookInbox.markProcessed(eventId, eventType);

            return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "eventId", eventId
            ));

        } catch (Exception ex) {
            log.error("[PAYPAL WEBHOOK] Failed to process event payload: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage()));
        }
    }

    private void handlePaymentSaleCompleted(JsonNode resource) {
        String subscriptionId = resource.path("billing_agreement_id").asText();
        String captureId = resource.path("id").asText();
        String totalStr = resource.path("amount").path("total").asText();
        String currency = resource.path("amount").path("currency").asText("USD");

        if (subscriptionId == null || subscriptionId.trim().isEmpty()) {
            log.warn("[PAYPAL WEBHOOK] PAYMENT.SALE.COMPLETED missing billing_agreement_id. Skipping savings deposit.");
            return;
        }

        BigDecimal amount = new BigDecimal(totalStr);
        log.info("[PAYPAL WEBHOOK] Processing savings deposit for subscription: {}, amount: {} {}", subscriptionId, amount, currency);

        savingsFacade.recordDepositBySubscription(subscriptionId, Money.of(amount, currency), captureId);
    }

    private void handleSubscriptionCancelled(JsonNode resource) {
        String subscriptionId = resource.path("id").asText();
        log.info("[PAYPAL WEBHOOK] Subscription {} cancelled. Pausing corresponding savings goal.", subscriptionId);
        savingsFacade.pauseGoalBySubscription(subscriptionId);
    }

    private void handleSubscriptionPaymentFailed(JsonNode resource) {
        String subscriptionId = resource.path("id").asText();
        log.warn("[PAYPAL WEBHOOK] Subscription {} payment failed. Per blueprint: zero penalty, streak allows quarterly miss.", subscriptionId);
    }
}
