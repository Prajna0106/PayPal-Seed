package com.paypalseed.engines.schedule;

import static com.paypalseed.engines.schedule.Fixtures.POLICY;
import static com.paypalseed.engines.schedule.Fixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engines.schedule.DueDaySuggester.Basis;
import com.paypalseed.engines.schedule.DueDaySuggester.DueDaySuggestion;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** TC-DDS-*, PROP-DDS-01 and TC-RTY-01/05 (retry date). */
class IncomeAwareTest {

    private static final YearMonth FIRST = YearMonth.of(2026, 4);
    private static final YearMonth LAST = YearMonth.of(2026, 9);

    /** Income of {@code dollars} on each listed day of every month in the profile. */
    private static IncomeProfile monthlyOn(long dollars, int... days) {
        Map<LocalDate, Long> income = new HashMap<>();
        for (YearMonth m = FIRST; !m.isAfter(LAST); m = m.plusMonths(1)) {
            for (int day : days) {
                income.merge(DueDates.dateOf(m, day), dollars * 100, Long::sum);
            }
        }
        return new IncomeProfile(FIRST, LAST, income);
    }

    @Test
    void tcDds01_anasSalesClusterOnThe6thTo8th() {
        DueDaySuggestion s = DueDaySuggester.suggest(Optional.of(monthlyOn(530, 6, 7, 8)), OptionalInt.empty());
        assertThat(s.dueDay()).isEqualTo(9);
        assertThat(s.basis()).isEqualTo(Basis.INCOME_PATTERN);
        assertThat(s.reason()).contains("9th");
    }

    @Test
    void tcDds02_biweeklyPayPicksARealPeakNotTheMean() {
        DueDaySuggestion s = DueDaySuggester.suggest(Optional.of(monthlyOn(800, 1, 15)), OptionalInt.empty());
        assertThat(s.dueDay()).isEqualTo(2);
    }

    @Test
    void tcDds03_windowWrapsAcrossMonthEnd() {
        DueDaySuggestion s = DueDaySuggester.suggest(Optional.of(monthlyOn(1_600, 30)), OptionalInt.empty());
        assertThat(s.dueDay()).isEqualTo(1);
    }

    @Test
    void tcDds04_tooLittleHistoryFallsBackToStatedPayday() {
        IncomeProfile twoMonths = new IncomeProfile(YearMonth.of(2026, 8), LAST, Map.of(d("2026-08-12"), 160_000L));
        DueDaySuggestion s = DueDaySuggester.suggest(Optional.of(twoMonths), OptionalInt.of(12));
        assertThat(s.dueDay()).isEqualTo(13);
        assertThat(s.basis()).isEqualTo(Basis.STATED_PAYDAY);
    }

    @Test
    void tcDds05_flatIncomeHasNoPattern() {
        Map<LocalDate, Long> daily = new HashMap<>();
        for (LocalDate day = FIRST.atDay(1); !day.isAfter(LAST.atEndOfMonth()); day = day.plusDays(1)) {
            daily.put(day, 5_000L);
        }
        DueDaySuggestion s = DueDaySuggester.suggest(Optional.of(new IncomeProfile(FIRST, LAST, daily)), OptionalInt.empty());
        assertThat(s.basis()).isEqualTo(Basis.DEFAULT);
    }

    @Property
    void propDds01_suggestionIsAlwaysADayEveryMonthHas(@ForAll @IntRange(min = 1, max = 31) int payday,
                                                        @ForAll @IntRange(min = 1, max = 31) int stated) {
        assertThat(DueDaySuggester.suggest(Optional.of(monthlyOn(900, payday)), OptionalInt.of(stated)).dueDay())
                .isBetween(1, 28);
        assertThat(DueDaySuggester.suggest(Optional.empty(), OptionalInt.of(stated)).dueDay()).isBetween(1, 28);
    }

    @Test
    void tcRty01_retryTheDayAfterIncomeUsuallyLands() {
        IncomeProfile incomeOn11th = monthlyOn(1_600, 11);
        Optional<LocalDate> retry = RetryPlanner.retryDate(d("2027-03-09"), d("2027-03-09"), Optional.of(incomeOn11th), POLICY);
        assertThat(retry).contains(d("2027-03-12"));
    }

    @Test
    void retryWithoutProfileUsesTheMiddleOfTheWindow() {
        assertThat(RetryPlanner.retryDate(d("2027-03-09"), d("2027-03-09"), Optional.empty(), POLICY))
                .contains(d("2027-03-12"));
    }

    @Test
    void tcRty05_failureReportedTooLateGetsNoRetry() {
        assertThat(RetryPlanner.retryDate(d("2027-03-09"), d("2027-03-13"), Optional.empty(), POLICY)).isEmpty();
    }
}
