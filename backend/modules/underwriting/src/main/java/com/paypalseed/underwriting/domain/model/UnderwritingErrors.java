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

    private UnderwritingErrors() {
    }
}
