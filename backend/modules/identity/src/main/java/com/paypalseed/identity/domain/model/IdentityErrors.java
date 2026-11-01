package com.paypalseed.identity.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Error codes the identity API can return (RFC 9457 problem types). */
public final class IdentityErrors {

    /** Also returned when a signed-in member has no record yet, so the answer never depends on whose id is probed. */
    public static final ErrorCode MEMBER_NOT_FOUND = ErrorCode.notFound("member-not-found", "No such member");
    public static final ErrorCode UNSUPPORTED_LANGUAGE =
            ErrorCode.validation("unsupported-language", "The language is not available; use en or es");
    public static final ErrorCode UNKNOWN_WALLET =
            ErrorCode.validation("unknown-wallet-preference", "The wallet must be PAYPAL or VENMO");
    public static final ErrorCode UNKNOWN_CONSENT_TYPE =
            ErrorCode.validation("unknown-consent-type", "The consent type is not known");
    public static final ErrorCode CONSENT_VERSION_STALE =
            ErrorCode.validation("consent-version-stale", "The text changed; show the current version and ask again");
    public static final ErrorCode INVALID_NAME = ErrorCode.validation("invalid-name", "The name must not be empty");
    public static final ErrorCode INVALID_PAYER_ID =
            ErrorCode.validation("invalid-payer-id", "A PayPal payer id is required");
    public static final ErrorCode INVALID_STAFF_ROLES =
            ErrorCode.validation("invalid-staff-roles", "Staff roles must be some of OFFICER, CHECKER and ADMIN");

    private IdentityErrors() {
    }
}
