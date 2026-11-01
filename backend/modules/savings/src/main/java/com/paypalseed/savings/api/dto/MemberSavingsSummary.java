package com.paypalseed.savings.api.dto;

/**
 * Summary of a member's savings consistency and monthly deposit capacity exposed to the lending module (Feature 2).
 */
public record MemberSavingsSummary(
    String memberId,
    int currentStreakMonths,
    int totalOnTimeDeposits,
    int missedDepositsInQuarter,
    long monthlySavingCents,
    long totalSavedBalanceCents,
    boolean microloanEligible
) {
}
