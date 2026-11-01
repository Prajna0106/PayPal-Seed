package com.paypalseed.identity.application;

import com.paypalseed.platform.kernel.ErrorCode;

/** Why a login was refused. The member web app shows a different message for each. */
public final class IdentityErrors {

    public static final ErrorCode PAYPAL_LOGIN_REJECTED = new ErrorCode("paypal-login-rejected",
            ErrorCode.Kind.UNAUTHENTICATED, "PayPal did not accept this login");
    public static final ErrorCode PAYPAL_IDENTITY_REFUSED = new ErrorCode("paypal-identity-refused",
            ErrorCode.Kind.UNAUTHENTICATED, "PayPal would not say who logged in");
    public static final ErrorCode ACCOUNT_SUSPENDED = new ErrorCode("account-suspended",
            ErrorCode.Kind.FORBIDDEN, "This account is suspended");

    private IdentityErrors() {
    }
}
