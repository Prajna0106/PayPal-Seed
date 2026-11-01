package com.paypalseed.deskquery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SavingStreakTest {

    private static LocalDate month(int year, int month) {
        return LocalDate.of(year, month, 1);
    }

    @Test
    void noDepositsMeansNoStreak() {
        assertThat(SavingStreak.of(List.of())).isZero();
    }

    @Test
    void consecutiveMonthsCount() {
        assertThat(SavingStreak.of(List.of(month(2026, 8), month(2026, 9), month(2026, 10)))).isEqualTo(3);
    }

    @Test
    void aGapEndsTheRun() {
        assertThat(SavingStreak.of(List.of(month(2026, 5), month(2026, 6), month(2026, 8), month(2026, 9)))).isEqualTo(2);
    }

    @Test
    void crossesTheYearBoundary() {
        assertThat(SavingStreak.of(List.of(month(2026, 11), month(2026, 12), month(2027, 1)))).isEqualTo(3);
    }

    @Test
    void orderAndDuplicatesDoNotMatter() {
        List<LocalDate> shuffled = List.of(month(2026, 10), month(2026, 8), month(2026, 9), month(2026, 9), month(2026, 10));

        assertThat(SavingStreak.of(shuffled)).isEqualTo(3);
    }

    @Test
    void anyDayOfTheMonthCountsForThatMonth() {
        assertThat(SavingStreak.of(List.of(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 2)))).isEqualTo(2);
    }

    @Test
    void depositMonthsAreUtcCalendarMonths() {
        assertThat(SavingStreak.monthOf(Instant.parse("2026-10-31T23:59:59Z"))).isEqualTo(month(2026, 10));
        assertThat(SavingStreak.monthOf(Instant.parse("2026-11-01T00:00:00Z"))).isEqualTo(month(2026, 11));
    }

    @Test
    void memberReferenceKeepsAUuidAndNamesAnythingElseStably() {
        UUID id = UUID.randomUUID();

        assertThat(MemberRefs.ofSavings(id.toString())).isEqualTo(id);
        assertThat(MemberRefs.ofSavings("ana@example.test")).isEqualTo(MemberRefs.ofSavings("ana@example.test"))
                .isNotEqualTo(MemberRefs.ofSavings("bo@example.test"));
        assertThat(MemberRefs.ofSavings("ana@example.test").toString()).doesNotContain("ana");
    }
}
