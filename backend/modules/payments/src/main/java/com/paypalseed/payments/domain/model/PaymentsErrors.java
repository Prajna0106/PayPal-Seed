package com.paypalseed.payments.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

public final class PaymentsErrors {

    public static final ErrorCode MANDATE_NOT_FOUND = ErrorCode.notFound("mandate-not-found", "No autopay mandate for this loan");
    public static final ErrorCode ORDER_NOT_FOUND = ErrorCode.notFound("order-not-found", "No such payment for this loan");
    public static final ErrorCode PAYPAL_REJECTED = ErrorCode.conflict("paypal-rejected", "PayPal refused the request");
    public static final ErrorCode NOT_ENOUGH_REFUNDABLE =
            ErrorCode.conflict("not-enough-refundable", "Not enough captured money to refund that amount");
    public static final ErrorCode INVALID_WEBHOOK =
            ErrorCode.validation("invalid-webhook", "Webhook could not be verified as coming from PayPal");

    public static final ErrorCode PAYOUT_REFERENCE_REUSED = ErrorCode.conflict("payout-reference-reused",
            "This payout reference was already used for a different amount or recipient");
    public static final ErrorCode SAVINGS_SUBSCRIPTION_NOT_FOUND =
            ErrorCode.notFound("savings-subscription-not-found", "This goal has no PayPal subscription");
    public static final ErrorCode SAVINGS_SUBSCRIPTION_ENDED = ErrorCode.conflict("savings-subscription-ended",
            "The goal's PayPal subscription was cancelled or has expired; a new one has to be approved");
    public static final ErrorCode TOPUP_NOT_FOUND = ErrorCode.notFound("topup-not-found", "No such top-up for this goal");
    public static final ErrorCode TRANSACTION_SEARCH_DISABLED = new ErrorCode("transaction-search-disabled",
            ErrorCode.Kind.PRECONDITION_FAILED, "Reading PayPal transaction history is switched off");

    private PaymentsErrors() {
    }
}
