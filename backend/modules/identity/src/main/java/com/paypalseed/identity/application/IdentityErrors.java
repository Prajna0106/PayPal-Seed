package com.paypalseed.identity.application;

import com.paypalseed.platform.kernel.ErrorCode;

/** Why a known member can't log in. */
public final class IdentityErrors {

    public static final ErrorCode ACCOUNT_SUSPENDED = new ErrorCode("account-suspended",
            ErrorCode.Kind.FORBIDDEN, "This account is suspended");

    private IdentityErrors() {
    }
}
