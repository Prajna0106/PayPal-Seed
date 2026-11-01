package com.paypalseed.savings.domain.model;

import java.time.YearMonth;
import java.util.*;

/**
 * Value Object calculating saving consistency and streak length.
 * Fairness rule from blueprint: allows at most 1 missed deposit per quarter without resetting streak.
 */
public record SavingsStreak(
    String memberId,
    int currentStreakMonths,
    int totalDepositsCount,
    int missedDepositsCount,
    boolean microloanEligible
) {

    public static final int MIN_MONTHS_FOR_MICROLOAN = 3;
    public static final int MAX_MISSED_PER_QUARTER = 1;

    public static SavingsStreak calculate(String memberId, List<YearMonth> depositMonths, YearMonth startMonth, YearMonth currentMonth) {
        Objects.requireNonNull(memberId, "memberId is required");
        Objects.requireNonNull(depositMonths, "depositMonths is required");
        Objects.requireNonNull(startMonth, "startMonth is required");
        Objects.requireNonNull(currentMonth, "currentMonth is required");

        Set<YearMonth> depositedSet = new HashSet<>(depositMonths);

        int totalDeposits = 0;
        int missedInCurrentQuarter = 0;
        int currentStreak = 0;
        int totalMissed = 0;

        YearMonth cursor = startMonth;
        int quarterCounter = 0;

        while (!cursor.isAfter(currentMonth)) {
            quarterCounter++;
            if (quarterCounter > 3) {
                quarterCounter = 1;
                missedInCurrentQuarter = 0; // reset for new quarter
            }

            if (depositedSet.contains(cursor)) {
                totalDeposits++;
                currentStreak++;
            } else {
                totalMissed++;
                missedInCurrentQuarter++;
                if (missedInCurrentQuarter > MAX_MISSED_PER_QUARTER) {
                    // Broke the allowed grace of 1 miss per quarter
                    currentStreak = 0;
                }
            }
            cursor = cursor.plusMonths(1);
        }

        // Eligibility: At least 3 on-time deposits and within grace limits
        boolean eligible = currentStreak >= MIN_MONTHS_FOR_MICROLOAN && totalDeposits >= MIN_MONTHS_FOR_MICROLOAN;

        return new SavingsStreak(memberId, currentStreak, totalDeposits, totalMissed, eligible);
    }
}
