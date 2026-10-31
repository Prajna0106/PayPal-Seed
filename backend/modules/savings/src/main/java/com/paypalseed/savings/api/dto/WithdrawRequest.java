package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;

public record WithdrawRequest(
    BigDecimal amount,
    String currency,
    String payPalPayoutId
) {
    public String getEffectiveCurrency() {
        return currency != null && !currency.trim().isEmpty() ? currency : "USD";
    }
}
