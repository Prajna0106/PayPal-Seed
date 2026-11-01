package com.paypalseed.deskquery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.deskquery.domain.model.NextDue.Instalment;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class NextDueTest {

    private static final List<Instalment> SCHEDULE = List.of(
            new Instalment(1, LocalDate.of(2026, 12, 20), 15_000),
            new Instalment(2, LocalDate.of(2027, 1, 20), 15_000),
            new Instalment(3, LocalDate.of(2027, 2, 20), 15_000),
            new Instalment(4, LocalDate.of(2027, 3, 20), 15_000));

    @Test
    void firstInstalmentIsNextWhenNothingIsPaid() {
        assertThat(NextDue.of(SCHEDULE, 60_000L)).contains(new NextDue(LocalDate.of(2026, 12, 20), 15_000));
    }

    @Test
    void paidInstalmentsAreSkippedOldestFirst() {
        assertThat(NextDue.of(SCHEDULE, 30_000L)).contains(new NextDue(LocalDate.of(2027, 2, 20), 15_000));
    }

    @Test
    void aPartPaymentLeavesTheRemainderOfTheInstalmentDue() {
        assertThat(NextDue.of(SCHEDULE, 52_500L)).contains(new NextDue(LocalDate.of(2026, 12, 20), 7_500));
        assertThat(NextDue.of(SCHEDULE, 37_500L)).contains(new NextDue(LocalDate.of(2027, 1, 20), 7_500));
    }

    @Test
    void nothingIsDueOnceTheLoanIsPaid() {
        assertThat(NextDue.of(SCHEDULE, 0L)).isEmpty();
    }

    @Test
    void withoutAScheduleNothingCanBeWorkedOut() {
        assertThat(NextDue.of(List.of(), 10_000L)).isEmpty();
    }

    @Test
    void anUnknownBalanceMeansNothingIsKnownToBePaid() {
        assertThat(NextDue.of(SCHEDULE, null)).contains(new NextDue(LocalDate.of(2026, 12, 20), 15_000));
    }

    @Test
    void aBalanceAboveTheScheduleTotalIsTreatedAsNothingPaid() {
        assertThat(NextDue.of(SCHEDULE, 75_000L)).contains(new NextDue(LocalDate.of(2026, 12, 20), 15_000));
    }

    @Test
    void aPausedScheduleKeepsTheTotalAndMovesTheDates() {
        List<Instalment> paused = List.of(
                new Instalment(1, LocalDate.of(2027, 1, 20), 15_000),
                new Instalment(2, LocalDate.of(2027, 2, 20), 15_000));

        assertThat(NextDue.of(paused, 30_000L)).contains(new NextDue(LocalDate.of(2027, 1, 20), 15_000));
    }
}
