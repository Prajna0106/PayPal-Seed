package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;

/**
 * Asks to take money out of a goal. Send an {@code Idempotency-Key} header: the amount is reserved, PayPal pays it
 * out, and the answer says where that stands.
 *
 * @param payPalPayoutId <b>Legacy.</b> A payout id the client made itself; accepted only in the dev, test and demo
 *                       profiles, where it books the withdrawal at once without calling PayPal.
 * @param wallet         {@code PAYPAL} or {@code VENMO}; empty means the member's saved wallet
 */
public record WithdrawRequest(
    BigDecimal amount,
    String currency,
    String payPalPayoutId,
    String wallet
) {
    /** The shape before withdrawals went through PayPal Payouts. */
    public WithdrawRequest(BigDecimal amount, String currency, String payPalPayoutId) {
        this(amount, currency, payPalPayoutId, null);
    }

    public String getEffectiveCurrency() {
        return currency != null && !currency.trim().isEmpty() ? currency : "USD";
    }
}
