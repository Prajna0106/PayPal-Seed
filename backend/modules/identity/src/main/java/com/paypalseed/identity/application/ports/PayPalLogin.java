package com.paypalseed.identity.application.ports;

/** Turns the one-time code from a Log in with PayPal redirect into who logged in. PayPal's tokens stay inside. */
public interface PayPalLogin {

    /** {@code subject} is the permanent key for a PayPal user; the other fields may be missing. */
    record PayPalIdentity(String subject, String payerId, String email, String name, boolean verifiedAccount) {
    }

    PayPalIdentity identify(String code, String codeVerifier, String redirectUri);
}
