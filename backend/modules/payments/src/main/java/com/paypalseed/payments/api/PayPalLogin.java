package com.paypalseed.payments.api;

import com.paypalseed.platform.kernel.ErrorCode;

/** Turns the one-time code from a Log in with PayPal redirect into who logged in. PayPal's tokens stay inside. */
public interface PayPalLogin {

    ErrorCode LOGIN_REJECTED = new ErrorCode("paypal-login-rejected", ErrorCode.Kind.UNAUTHENTICATED,
            "PayPal did not accept this login");
    ErrorCode IDENTITY_REFUSED = new ErrorCode("paypal-identity-refused", ErrorCode.Kind.UNAUTHENTICATED,
            "PayPal would not say who logged in");

    /** {@code subject} is the permanent key for a PayPal user; the other fields may be missing. */
    record PayPalIdentity(String subject, String payerId, String email, String name, boolean verifiedAccount) {
    }

    PayPalIdentity identify(String code, String codeVerifier, String redirectUri);
}
