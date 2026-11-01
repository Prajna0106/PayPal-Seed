package com.paypalseed.underwriting.income;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.underwriting.domain.model.IncomeBuckets;
import com.paypalseed.underwriting.domain.model.IncomeBuckets.Bucket;
import com.paypalseed.underwriting.domain.model.IncomeEntry;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncomeBucketsTest {

    /** The window is 2026-08-11 .. 2026-11-09: 13 buckets of 7 days, the last ending the day before today. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final LocalDate FIRST = LocalDate.of(2026, 8, 11);

    private static IncomeEntry credit(String id, LocalDate date, long net) {
        return new IncomeEntry(id, date, net);
    }

    @Test
    void alwaysReturnsThirteenWeeksStartingAtTheWindowAndOneWeekApart() {
        List<Bucket> weeks = IncomeBuckets.weekly(TODAY, List.of());

        assertThat(weeks).hasSize(13);
        assertThat(weeks.get(0).weekStart()).isEqualTo(FIRST);
        assertThat(weeks.get(12).weekStart()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(weeks).extracting(Bucket::inflowCents).containsOnly(0L);
        for (int i = 1; i < weeks.size(); i++) {
            assertThat(weeks.get(i).weekStart()).isEqualTo(weeks.get(i - 1).weekStart().plusDays(7));
        }
    }

    @Test
    void theSeventhAndEighthDayOfABucketLandInNeighbouringWeeks() {
        List<Bucket> weeks = IncomeBuckets.weekly(TODAY, List.of(
                credit("last-day-of-week-0", FIRST.plusDays(6), 100),
                credit("first-day-of-week-1", FIRST.plusDays(7), 200),
                credit("last-day-of-week-1", FIRST.plusDays(13), 300),
                credit("first-day-of-week-2", FIRST.plusDays(14), 400)));

        assertThat(weeks.get(0).inflowCents()).isEqualTo(100);
        assertThat(weeks.get(1).inflowCents()).isEqualTo(500);
        assertThat(weeks.get(2).inflowCents()).isEqualTo(400);
    }

    @Test
    void partialFirstAndLastWeeksAreNotScaledUp() {
        // Activity only on the very first and the very last day the window covers.
        List<Bucket> weeks = IncomeBuckets.weekly(TODAY, List.of(
                credit("first", FIRST, 12_000),
                credit("last", TODAY.minusDays(1), 8_000)));

        assertThat(weeks.get(0).inflowCents()).isEqualTo(12_000);
        assertThat(weeks.get(12).inflowCents()).isEqualTo(8_000);
        assertThat(weeks.subList(1, 12)).extracting(Bucket::inflowCents).containsOnly(0L);
    }

    @Test
    void todayAndTheDayBeforeTheWindowStayOut() {
        List<Bucket> weeks = IncomeBuckets.weekly(TODAY, List.of(
                credit("today-is-incomplete", TODAY, 5_000),
                credit("too-old", FIRST.minusDays(1), 5_000),
                credit("future", TODAY.plusDays(3), 5_000)));

        assertThat(weeks).extracting(Bucket::inflowCents).containsOnly(0L);
    }

    @Test
    void creditsAreSummedAsGivenSoFeesAndRefundsAlreadyNettedByPaymentsCount() {
        // Payments hands over net = gross - fee - refunded: 20_000 - 590 and 10_000 - 320 - 4_000.
        List<Bucket> weeks = IncomeBuckets.weekly(TODAY, List.of(
                credit("A", FIRST.plusDays(2), 19_410),
                credit("C", FIRST.plusDays(3), 5_680)));

        assertThat(weeks.get(0).inflowCents()).isEqualTo(25_090);
    }

    @Test
    void aFullyRefundedOrNegativeCreditAddsNothing() {
        List<Bucket> weeks = IncomeBuckets.weekly(TODAY, List.of(
                credit("refunded", FIRST.plusDays(1), 0),
                credit("negative", FIRST.plusDays(1), -300),
                credit("real", FIRST.plusDays(1), 700)));

        assertThat(weeks.get(0).inflowCents()).isEqualTo(700);
    }

    @Test
    void aLineOnAPayPalWindowEdgeSeenTwiceCountsOnce() {
        LocalDate edge = FIRST.plusDays(31);
        List<Bucket> weeks = IncomeBuckets.weekly(TODAY, List.of(
                credit("B", edge, 3_000),
                credit("B", edge, 3_000)));

        assertThat(weeks.stream().mapToLong(Bucket::inflowCents).sum()).isEqualTo(3_000);
        assertThat(weeks.get(4).inflowCents()).isEqualTo(3_000);
    }

    @Test
    void theWindowMovesWithTheDateItIsCutFor() {
        IncomeEntry credit = credit("X", LocalDate.of(2026, 11, 2), 900);

        assertThat(IncomeBuckets.weekly(TODAY, List.of(credit)).get(11).inflowCents()).isEqualTo(900);
        assertThat(IncomeBuckets.weekly(TODAY.plusDays(7), List.of(credit)).get(11).inflowCents()).isZero();
        assertThat(IncomeBuckets.weekly(TODAY.plusDays(7), List.of(credit)).get(10).inflowCents()).isEqualTo(900);
    }
}
