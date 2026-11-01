package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;

/**
 * Request payload for the F1/F2 AI Savings & Microloan Term Coach (`POST /api/v1/savings/coach/suggest`).
 */
public record CoachSuggestionRequest(
    String memberId,
    String goalName,
    BigDecimal targetAmount,
    BigDecimal statedMonthlyIncome,
    BigDecimal statedMonthlyExpenses,
    String currency
) {
    public String effectiveCurrency() {
        return (currency == null || currency.isBlank()) ? "USD" : currency;
    }
}
