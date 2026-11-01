package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateGoalRequest(
    String name,
    BigDecimal targetAmount,
    BigDecimal monthlyAmount,
    String currency,
    LocalDate targetDate,
    String payPalSubscriptionId
) {
    public String getEffectiveCurrency() {
        return currency != null && !currency.trim().isEmpty() ? currency : "USD";
    }
}
