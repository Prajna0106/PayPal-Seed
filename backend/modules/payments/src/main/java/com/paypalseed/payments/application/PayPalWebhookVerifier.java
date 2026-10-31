package com.paypalseed.payments.application;

import java.util.Map;

public interface PayPalWebhookVerifier {
    /**
     * Verifies the PayPal signature headers against the payload.
     * In development/sandbox/mock mode, this can return true for mock test signatures.
     */
    boolean verifySignature(Map<String, String> headers, String rawPayload);
}
