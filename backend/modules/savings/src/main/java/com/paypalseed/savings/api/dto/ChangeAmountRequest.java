package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;

/** A new monthly amount. PayPal may ask the member to agree to it: the goal then carries an {@code approvalUrl}. */
public record ChangeAmountRequest(
    BigDecimal monthlyAmount,
    String currency
) {
    public String getEffectiveCurrency() {
        return currency != null && !currency.trim().isEmpty() ? currency : "USD";
    }
}
