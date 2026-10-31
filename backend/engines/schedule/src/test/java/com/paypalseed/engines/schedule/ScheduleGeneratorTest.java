package com.paypalseed.engines.schedule;

import static com.paypalseed.engines.schedule.Fixtures.POLICY;
import static com.paypalseed.engines.schedule.Fixtures.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.engines.schedule.RepaymentPolicy.RemainderPlacement;
import com.paypalseed.platform.kernel.DomainException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleGeneratorTest {

    private static List<Long> dollars(Schedule s) {
        return s.instalments().stream().map(i -> i.amountCents() / 100).toList();
    }

    private static List<LocalDate> dates(Schedule s) {
        return s.instalments().stream().map(Instalment::dueDate).toList();
    }

    @Test
    void tcGen01_equalSplit() {
        Schedule s = Fixtures.ana();
        assertThat(dollars(s)).containsExactly(150L, 150L, 150L, 150L, 150L, 150L, 150L, 150L);
        assertThat(s.instalments().get(0).dueDate()).isEqualTo(d("2026-12-09"));
        assertThat(s.version()).isEqualTo(1);
    }

    @Test
    void tcGen02_remainderAtTheBack() {
        assertThat(dollars(ScheduleGenerator.generate(100_000, 3, 9, Fixtures.DISBURSED, POLICY)))
                .containsExactly(333L, 333L, 334L);
    }

    @Test
    void tcGen03_remainderSpreadOneDollarAtATime() {
        assertThat(dollars(ScheduleGenerator.generate(100_000, 7, 9, Fixtures.DISBURSED, POLICY)))
                .containsExactly(142L, 143L, 143L, 143L, 143L, 143L, 143L);
    }

    @Test
    void tcGen04_frontPlacementPutsLargerAmountsFirst() {
        RepaymentPolicy front = new RepaymentPolicy("front", 5, 14, 3, 3, 7,
                RepaymentPolicy.FutureInstalmentRule.LAST_INSTALLMENT, RemainderPlacement.FRONT,
                2, 4, 2, 14, 6, 2_500, 3, 36);
        assertThat(dollars(ScheduleGenerator.generate(100_000, 7, 9, Fixtures.DISBURSED, front)))
                .containsExactly(143L, 143L, 143L, 143L, 143L, 143L, 142L);
    }

    @Test
    void tcGen05_moreInstalmentsThanDollarsIsRejected() {
        assertThatThrownBy(() -> ScheduleGenerator.generate(500, 6, 9, Fixtures.DISBURSED, POLICY))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ScheduleErrors.INVALID_TERM);
    }

    @Test
    void tcGen06_centsInPrincipalAreRejected() {
        assertThatThrownBy(() -> ScheduleGenerator.generate(120_050, 8, 9, Fixtures.DISBURSED, POLICY))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ScheduleErrors.PRINCIPAL_NOT_WHOLE_DOLLARS);
    }

    @Test
    void tcDue01_day31ClampsWithoutDrifting() {
        Schedule s = ScheduleGenerator.generate(40_000, 4, 31, d("2026-12-20"), POLICY);
        assertThat(dates(s)).containsExactly(d("2027-01-31"), d("2027-02-28"), d("2027-03-31"), d("2027-04-30"));
    }

    @Test
    void tcDue02_leapYear() {
        Schedule s = ScheduleGenerator.generate(30_000, 3, 30, d("2027-12-20"), POLICY);
        assertThat(dates(s)).containsExactly(d("2028-01-30"), d("2028-02-29"), d("2028-03-30"));
    }

    @Test
    void tcDue03_firstDueRespectsMinimumLead() {
        Schedule s = ScheduleGenerator.generate(30_000, 3, 5, d("2026-11-30"), POLICY);
        assertThat(s.instalments().get(0).dueDate()).isEqualTo(d("2027-01-05"));
    }

    @Test
    void tcDue04_firstDueInSameMonthWhenLeadAllows() {
        Schedule s = ScheduleGenerator.generate(30_000, 3, 25, d("2026-11-10"), POLICY);
        assertThat(s.instalments().get(0).dueDate()).isEqualTo(d("2026-11-25"));
    }

    @Test
    void tcDue05_daylightSavingChangeDoesNotShiftDates() {
        Schedule s = ScheduleGenerator.generate(30_000, 3, 14, d("2027-03-13"), POLICY);
        assertThat(dates(s)).containsExactly(d("2027-04-14"), d("2027-05-14"), d("2027-06-14"));
    }

    @Test
    void invalidDueDayIsRejected() {
        assertThatThrownBy(() -> ScheduleGenerator.generate(30_000, 3, 32, Fixtures.DISBURSED, POLICY))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void dueDatesHelperClampsToMonthEnd() {
        assertThat(DueDates.dateOf(YearMonth.of(2027, 2), 31)).isEqualTo(d("2027-02-28"));
    }
}
