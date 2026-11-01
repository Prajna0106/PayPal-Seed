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
    public static final ErrorCode RESCHEDULE_PREVIEW_CHANGED = ErrorCode.conflict("reschedule-preview-changed",
            "The schedule changed since the approved preview; the member must confirm the new numbers");
    public static final ErrorCode HOLD_CONFLICT =
            ErrorCode.conflict("hold-conflict", "Another case already holds this instalment");
    public static final ErrorCode HOLD_OUT_OF_RANGE =
            ErrorCode.validation("hold-out-of-range", "A hold may last until at most 10 days after the due date");
    public static final ErrorCode HOLD_NOT_APPLICABLE =
            ErrorCode.validation("hold-not-applicable", "The instalment is not owed in the current schedule version");

    // Feature 2: Savings-linked microloans
    public static final ErrorCode APPLICATION_NOT_FOUND =
            ErrorCode.notFound("application-not-found", "Loan application not found");
    public static final ErrorCode INELIGIBLE_FOR_LOAN =
            ErrorCode.validation("ineligible-for-loan", "Member is not currently eligible for a microloan");
    public static final ErrorCode SIZING_RULE_VIOLATION =
            ErrorCode.validation("sizing-rule-violation", "Requested loan exceeds safe affordability or tier limits");
    public static final ErrorCode MAKER_CHECKER_SAME_OFFICER =
            ErrorCode.conflict("maker-checker-same-officer", "Two different loan officers must approve a microloan");
    public static final ErrorCode APPLICATION_INVALID_STATE =
            ErrorCode.conflict("application-invalid-state", "Loan application is not in a valid state for this action");
    public static final ErrorCode PAYOUT_RECIPIENT_MISSING = ErrorCode.conflict("payout-recipient-missing",
            "There is no PayPal account on file to pay this loan into");
    public static final ErrorCode COOLING_OFF_EXPIRED =
            ErrorCode.conflict("cooling-off-expired", "The 48-hour cooling-off return window has expired");

    private LendingErrors() {
    }
}
