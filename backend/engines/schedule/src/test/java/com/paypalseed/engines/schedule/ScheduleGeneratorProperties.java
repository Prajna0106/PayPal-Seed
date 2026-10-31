package com.paypalseed.engines.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class ScheduleGeneratorProperties {

    @Property
    void propGen01_splitIsExactAndAsEvenAsPossible(
            @ForAll @LongRange(min = 100, max = 5_000) long principalDollars,
            @ForAll @IntRange(min = 1, max = 36) int count,
            @ForAll @IntRange(min = 1, max = 31) int dueDay) {
        Assume.that(principalDollars >= count);
        Schedule s = ScheduleGenerator.generate(principalDollars * 100, count, dueDay, Fixtures.DISBURSED, Fixtures.POLICY);
        List<Long> amounts = s.instalments().stream().map(Instalment::amountCents).toList();

        assertThat(amounts.stream().mapToLong(Long::longValue).sum()).isEqualTo(principalDollars * 100);
        assertThat(amounts).allSatisfy(a -> assertThat(a % 100).isZero());
        long max = amounts.stream().mapToLong(Long::longValue).max().orElseThrow();
        long min = amounts.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertThat(max - min).isLessThanOrEqualTo(100);
        long ceilDollars = (principalDollars + count - 1) / count;
        assertThat(max).isEqualTo(ceilDollars * 100);
        assertThat(amounts).isSorted(); // BACK placement: non-decreasing
    }

    @Property
    void propDue01_oneDueDatePerConsecutiveMonthOnTheClampedDay(
            @ForAll @IntRange(min = 1, max = 31) int dueDay,
            @ForAll @IntRange(min = 0, max = 1_800) int daysAfter2026,
            @ForAll @IntRange(min = 1, max = 18) int count) {
        LocalDate disbursed = LocalDate.of(2026, 1, 1).plusDays(daysAfter2026);
        Schedule s = ScheduleGenerator.generate(count * 10_000L, count, dueDay, disbursed, Fixtures.POLICY);

        LocalDate first = s.instalments().get(0).dueDate();
        assertThat(ChronoUnit.DAYS.between(disbursed, first)).isGreaterThanOrEqualTo(Fixtures.POLICY.minFirstDueLeadDays());
        for (int k = 0; k < count; k++) {
            LocalDate due = s.instalments().get(k).dueDate();
            YearMonth month = YearMonth.from(first).plusMonths(k);
            assertThat(YearMonth.from(due)).isEqualTo(month);
            assertThat(due.getDayOfMonth()).isEqualTo(Math.min(dueDay, month.lengthOfMonth()));
        }
        // the first due date is the earliest one allowed: one month earlier would be too soon
        LocalDate monthBefore = DueDates.dateOf(YearMonth.from(first).minusMonths(1), dueDay);
        assertThat(ChronoUnit.DAYS.between(disbursed, monthBefore)).isLessThan(Fixtures.POLICY.minFirstDueLeadDays());
    }
}
