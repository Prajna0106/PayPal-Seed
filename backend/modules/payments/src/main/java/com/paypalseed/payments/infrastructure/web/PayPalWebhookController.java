package com.paypalseed.payments.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.SavingsFacade;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PayPal webhook ingress (ingress role): verify the signature, store in the inbox, return 200 fast
 * (NFR-02). Nothing is acted on here; the inbox processor does the work. Unverified events are
 * refused and never stored.
 */
@RestController
public class PayPalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookController.class);
    private static final int MAX_BODY_CHARS = 256 * 1024;

    public record Ack(String status, String eventId) {
        public Ack(String status) {
            this(status, null);
        }
    }

    private final PayPalGateway paypal;
    private final InboxRepository inbox;
    private final ObjectMapper json;
    private final SavingsFacade savingsFacade;

    public PayPalWebhookController(PayPalGateway paypal, InboxRepository inbox, ObjectMapper json) {
        this(paypal, inbox, json, null);
    }

    @Autowired
    public PayPalWebhookController(
            PayPalGateway paypal,
            InboxRepository inbox,
            ObjectMapper json,
            @Autowired(required = false) SavingsFacade savingsFacade
    ) {
        this.paypal = paypal;
        this.inbox = inbox;
        this.json = json;
        this.savingsFacade = savingsFacade;
    }

    @PostMapping(path = {"/api/v1/webhooks/paypal", "/api/v1/payments/webhooks"}, consumes = "application/json")
    public Ack receive(@RequestHeader HttpHeaders headers, @RequestBody String rawBody) {
        if (rawBody.length() > MAX_BODY_CHARS) {
            throw new DomainException(PaymentsErrors.INVALID_WEBHOOK, "Webhook body too large");
        }
        Map<String, String> transmission = new HashMap<>();
        headers.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("paypal-") && !values.isEmpty()) {
                transmission.put(lower, values.get(0));
            }
        });
        if (!paypal.verifyWebhook(transmission, rawBody)) {
            log.warn("Rejected unverifiable PayPal webhook (transmission {})", transmission.get("paypal-transmission-id"));
            throw new DomainException(PaymentsErrors.INVALID_WEBHOOK, "Signature verification failed");
        }
        JsonNode event;
        try {
            event = json.readTree(rawBody);
        } catch (IOException e) {
            throw new DomainException(PaymentsErrors.INVALID_WEBHOOK, "Body is not JSON");
        }
        String id = event.path("id").asText("");
        String type = event.path("event_type").asText("");
        if (id.isBlank() || type.isBlank()) {
            throw new DomainException(PaymentsErrors.INVALID_WEBHOOK, "Missing id or event_type");
        }

        boolean inserted = inbox.insert(id, type, rawBody);

        if (inserted && savingsFacade != null) {
            routeSavingsEvent(type, event.path("resource"));
        }

        return new Ack(inserted ? "accepted" : "duplicate", id);
    }

    private void routeSavingsEvent(String eventType, JsonNode resource) {
        try {
            switch (eventType) {
                case "PAYMENT.SALE.COMPLETED" -> {
                    String subscriptionId = resource.path("billing_agreement_id").asText("");
                    if (!subscriptionId.isBlank()) {
                        String captureId = resource.path("id").asText("");
                        String totalStr = resource.path("amount").path("total").asText("");
                        String currency = resource.path("amount").path("currency").asText("USD");
                        if (!totalStr.isBlank()) {
                            Money amount = Money.of(new BigDecimal(totalStr), currency);
                            savingsFacade.recordDepositBySubscription(subscriptionId, amount, captureId);
                        }
                    }
                }
                case "BILLING.SUBSCRIPTION.CANCELLED" -> {
                    String subscriptionId = resource.path("id").asText("");
                    if (!subscriptionId.isBlank()) {
                        savingsFacade.pauseGoalBySubscription(subscriptionId);
                    }
                }
                default -> {
                    // Other event types handled by inbox poller / normalizer
                }
            }
        } catch (Exception ex) {
            log.warn("[PAYPAL WEBHOOK] Failed to process savings event {}: {}", eventType, ex.getMessage());
        }
    }
}
