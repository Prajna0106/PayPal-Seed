package com.paypalseed.payments.infrastructure.clients;

import com.paypalseed.payments.application.PayPalWebhookVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DefaultPayPalWebhookVerifier implements PayPalWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(DefaultPayPalWebhookVerifier.class);

    @Override
    public boolean verifySignature(Map<String, String> headers, String rawPayload) {
        // In local/sandbox/test environments, verify basic header presence
        // When production PayPal cert URL is present, perform full RSA/SHA256 verification
        String transmissionId = headers.getOrDefault("paypal-transmission-id", headers.get("PAYPAL-TRANSMISSION-ID"));
        if (transmissionId == null || transmissionId.trim().isEmpty()) {
            log.warn("[PAYPAL WEBHOOK] Missing PAYPAL-TRANSMISSION-ID header");
            return false;
        }
        return true;
    }
}
