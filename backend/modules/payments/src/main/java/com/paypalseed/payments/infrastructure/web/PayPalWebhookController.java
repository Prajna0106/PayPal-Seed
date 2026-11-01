package com.paypalseed.payments.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository;
import com.paypalseed.platform.kernel.DomainException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PayPal webhook ingress (ingress role): verify the signature, store in the inbox, return 200 fast
 * (NFR-02). Nothing is acted on here; the inbox processor does the work (savings deposits and subscription
 * changes included, as events the savings module listens to). Unverified events are refused and never stored.
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

    public PayPalWebhookController(PayPalGateway paypal, InboxRepository inbox, ObjectMapper json) {
        this.paypal = paypal;
        this.inbox = inbox;
        this.json = json;
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
        return new Ack(inserted ? "accepted" : "duplicate", id);
    }
}
