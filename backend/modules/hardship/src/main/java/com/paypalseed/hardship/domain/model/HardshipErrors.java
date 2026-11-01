package com.paypalseed.hardship.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Error codes the hardship API can return (RFC 9457 problem types, 02-design §6.5). */
public final class HardshipErrors {

    /** Also returned for another member's loan or case, so ids cannot be probed. */
    public static final ErrorCode NO_OPEN_OFFER =
            ErrorCode.notFound("hardship-no-open-offer", "There is no open hardship offer for this loan");
    public static final ErrorCode CASE_NOT_FOUND = ErrorCode.notFound("hardship-case-not-found", "No such hardship case");
    public static final ErrorCode NOT_ELIGIBLE =
            ErrorCode.validation("hardship-not-eligible", "The loan cannot get hardship options right now");
    public static final ErrorCode POLICY_VIOLATION =
            ErrorCode.validation("hardship-policy-violation", "The option is outside the hardship policy");
    public static final ErrorCode UNKNOWN_OPTION =
            ErrorCode.validation("hardship-unknown-option", "The option is not part of the active offer");
    public static final ErrorCode CASE_CLOSED = ErrorCode.conflict("hardship-case-closed", "The case does not accept this action");
    public static final ErrorCode NOT_AWAITING_DECISION =
            ErrorCode.conflict("hardship-not-awaiting-decision", "The case has no open approval request");
    public static final ErrorCode INVALID_FILTER =
            ErrorCode.validation("hardship-invalid-filter", "The queue filter or sort is not allowed");

    private HardshipErrors() {
    }
}
