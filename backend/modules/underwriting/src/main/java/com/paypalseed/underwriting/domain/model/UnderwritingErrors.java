package com.paypalseed.underwriting.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Error codes returned by the underwriting (Feature 3: Fair Loan Check) module. */
public final class UnderwritingErrors {

    public static final ErrorCode CONSENT_REQUIRED =
        ErrorCode.validation("consent-required", "Active member consent is required before running a Fair Loan Check");
    public static final ErrorCode ASSESSMENT_NOT_FOUND =
        ErrorCode.notFound("assessment-not-found", "Fair Loan Check assessment not found");
    public static final ErrorCode INVALID_DECISION =
        ErrorCode.validation("invalid-officer-decision", "Invalid officer decision on Fair Loan Check");
    public static final ErrorCode PAYPAL_ACCOUNT_NOT_LINKED =
        ErrorCode.validation("paypal-account-not-linked", "Link your PayPal account first");
    /** Retryable: nothing was computed, because stale or partial income must never feed a decision. */
    public static final ErrorCode INCOME_SOURCE_UNAVAILABLE = new ErrorCode(
        "income-source-unavailable", ErrorCode.Kind.UNAVAILABLE, "Your PayPal income could not be read; try again shortly");

    private UnderwritingErrors() {
    }
}
