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

    private PaymentsErrors() {
    }
}
