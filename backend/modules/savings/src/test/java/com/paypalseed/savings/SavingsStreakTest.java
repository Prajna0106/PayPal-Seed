package com.paypalseed.savings;

import com.paypalseed.savings.domain.model.SavingsStreak;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SavingsStreakTest {

    @Test
    void fourConsecutiveDepositsUnlocksMicroloanEligibility() {
        YearMonth start = YearMonth.of(2026, 1);
        YearMonth current = YearMonth.of(2026, 4);

        List<YearMonth> deposits = List.of(
            YearMonth.of(2026, 1),
            YearMonth.of(2026, 2),
            YearMonth.of(2026, 3),
            YearMonth.of(2026, 4)
        );

        SavingsStreak streak = SavingsStreak.calculate("ana-baker", deposits, start, current);

        assertEquals(4, streak.currentStreakMonths());
        assertEquals(4, streak.totalDepositsCount());
        assertEquals(0, streak.missedDepositsCount());
        assertTrue(streak.microloanEligible());
    }

    @Test
    void allowsOneMissedDepositPerQuarterWithoutResettingStreak() {
        YearMonth start = YearMonth.of(2026, 1);
        YearMonth current = YearMonth.of(2026, 4);

        // Deposited Jan, missed Feb, deposited Mar and Apr
        List<YearMonth> deposits = List.of(
            YearMonth.of(2026, 1),
            YearMonth.of(2026, 3),
            YearMonth.of(2026, 4)
        );

        SavingsStreak streak = SavingsStreak.calculate("ana-baker", deposits, start, current);

        assertEquals(3, streak.totalDepositsCount());
        assertEquals(1, streak.missedDepositsCount());
        // Streak is preserved because 1 miss per quarter is within the grace policy
        assertTrue(streak.microloanEligible());
    }

    @Test
    void twoMissedDepositsInOneQuarterResetsStreak() {
        YearMonth start = YearMonth.of(2026, 1);
        YearMonth current = YearMonth.of(2026, 3);

        // Deposited Jan, missed Feb and Mar (2 misses in Q1)
        List<YearMonth> deposits = List.of(
            YearMonth.of(2026, 1)
        );

        SavingsStreak streak = SavingsStreak.calculate("ana-baker", deposits, start, current);

        assertEquals(0, streak.currentStreakMonths());
        assertEquals(2, streak.missedDepositsCount());
        assertFalse(streak.microloanEligible());
    }
}
