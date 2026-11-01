package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;

/**
 * Grounded response from the F1/F2 Savings & Microloan Term Coach.
 * Every numeric field is computed by deterministic code; only the bilingual coaching message
 * is narrated by the model (or deterministic fallback template).
 */
public record CoachSuggestionResponse(
    String memberId,
    String goalName,
    String currency,
    BigDecimal targetAmount,
    BigDecimal statedMonthlyIncome,
    BigDecimal statedMonthlyExpenses,
    BigDecimal disposableMonthlyIncome,
    BigDecimal suggestedMonthlySaving,
    int streakMonthsToUnlock,
    BigDecimal totalSavedAfterStreak,
    String unlockedTier,
    BigDecimal unlockedLoanAmount,
    BigDecimal maxMonthlyInstalment,
    int suggestedLoanTermMonths,
    BigDecimal suggestedMonthlyInstalment,
    String messageEn,
    String messageEs,
    boolean modelGenerated
) {
}
