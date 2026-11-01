package com.paypalseed.reserve.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Stable error codes of the reserve module; each becomes a problem {@code type}. */
public final class ReserveErrors {

    public static final ErrorCode DONATION_AMOUNT_INVALID =
            ErrorCode.validation("donation-amount-invalid", "The donation amount is not valid");
    public static final ErrorCode COVER_AMOUNT_EXCEEDS_REQUEST_CAP =
            ErrorCode.validation("cover-amount-exceeds-request-cap", "The amount is above the limit for one cover request");
    public static final ErrorCode COVER_MEMBER_CAP_EXCEEDED =
            ErrorCode.validation("cover-member-cap-exceeded", "The member has reached the yearly cover limit");
    public static final ErrorCode COVER_LOAN_NOT_FOUND =
            ErrorCode.notFound("cover-loan-not-found", "No such loan for this member");
    public static final ErrorCode COVER_LOAN_NOT_ACTIVE =
            ErrorCode.validation("cover-loan-not-active", "Only an active loan can be covered");
    public static final ErrorCode COVER_EXCEEDS_OUTSTANDING =
            ErrorCode.validation("cover-exceeds-outstanding", "The amount is more than the loan still owes");
    public static final ErrorCode RESERVE_INSUFFICIENT_FUNDS =
            ErrorCode.conflict("reserve-insufficient-funds", "The reserve does not hold enough for this cover");
    public static final ErrorCode COVER_REQUEST_OPEN =
            ErrorCode.conflict("cover-request-open", "This loan already has a cover request waiting for a decision");
    public static final ErrorCode COVER_REQUEST_NOT_FOUND =
            ErrorCode.notFound("cover-request-not-found", "Cover request not found");
    public static final ErrorCode COVER_NOT_AWAITING_DECISION =
            ErrorCode.conflict("cover-not-awaiting-decision", "This cover request is not waiting for a decision");
    public static final ErrorCode FUND_LIMIT_REACHED =
            ErrorCode.conflict("reserve-limit-reached", "The reserve cannot hold more than the system supports");

    private ReserveErrors() {
    }
}
