package com.paypalseed.savings.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Error codes the savings API can return (RFC 9457 problem types). */
public final class SavingsErrors {

    public static final ErrorCode GOAL_NOT_FOUND = ErrorCode.notFound("goal-not-found", "Savings goal not found");
    public static final ErrorCode WITHDRAWAL_NOT_FOUND = ErrorCode.notFound("withdrawal-not-found", "Withdrawal not found");
    public static final ErrorCode IDEMPOTENCY_KEY_REQUIRED =
            ErrorCode.validation("idempotency-key-required", "This request needs an Idempotency-Key header");
    public static final ErrorCode LEGACY_ID_NOT_ALLOWED = ErrorCode.validation("legacy-id-not-allowed",
            "A PayPal id made by the client is only accepted in development, test and demo; Seed creates it");
    public static final ErrorCode AMOUNT_NOT_WHOLE_DOLLARS = ErrorCode.validation("amount-not-whole-dollars",
            "A PayPal subscription saves a whole number of dollars each month");
    public static final ErrorCode PAYOUT_RECIPIENT_MISSING = ErrorCode.conflict("payout-recipient-missing",
            "There is no PayPal account on file to pay this money into");
    public static final ErrorCode SUBSCRIPTION_UNAVAILABLE = ErrorCode.conflict("subscription-unavailable",
            "This goal saves from Venmo with one-off top-ups, not a subscription");
    public static final ErrorCode TOPUP_NOT_ALLOWED = ErrorCode.conflict("topup-not-allowed",
            "This goal cannot take a top-up right now");

    private SavingsErrors() {
    }
}
