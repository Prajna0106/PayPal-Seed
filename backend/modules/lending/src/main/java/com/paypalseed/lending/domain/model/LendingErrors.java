package com.paypalseed.lending.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Error codes the lending API can return (RFC 9457 problem types). */
public final class LendingErrors {

    public static final ErrorCode LOAN_NOT_FOUND = ErrorCode.notFound("loan-not-found", "Loan not found");
    public static final ErrorCode LOAN_NOT_ACTIVE = ErrorCode.conflict("loan-not-active", "The loan is not active");
    public static final ErrorCode AMOUNT_NOT_WHOLE_DOLLARS =
            ErrorCode.validation("amount-not-whole-dollars", "Payments are accepted in whole dollars");
    public static final ErrorCode AMOUNT_EXCEEDS_OUTSTANDING =
            ErrorCode.validation("amount-exceeds-outstanding", "Amount is more than the loan's outstanding balance");
    public static final ErrorCode AMOUNT_TOO_SMALL = ErrorCode.validation("amount-too-small", "Amount must be at least $1");
    public static final ErrorCode NO_OVERPAYMENT = ErrorCode.validation("no-overpayment", "There is no overpayment to refund");
    public static final ErrorCode REFUND_EXCEEDS_OVERPAYMENT =
            ErrorCode.validation("refund-exceeds-overpayment", "Refund is more than the overpayment held");
    public static final ErrorCode AUTOPAY_ALREADY_ACTIVE =
            ErrorCode.conflict("autopay-already-active", "Autopay is already on for this loan");
    public static final ErrorCode SCHEDULE_VERSION_NOT_FOUND =
            ErrorCode.notFound("schedule-version-not-found", "No such schedule version");
    public static final ErrorCode COB_OUT_OF_ORDER =
            ErrorCode.conflict("cob-out-of-order", "Close of business must run for each business date in order");
    public static final ErrorCode COB_RUN_NOT_FOUND =
            ErrorCode.notFound("cob-run-not-found", "No close-of-business run for that date");
    public static final ErrorCode COB_IN_THE_FUTURE =
            ErrorCode.validation("cob-in-the-future", "Close of business cannot run for a future business date");

    private LendingErrors() {
    }
}
