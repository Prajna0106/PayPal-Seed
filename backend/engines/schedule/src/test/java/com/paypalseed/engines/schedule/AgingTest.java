package com.paypalseed.engines.schedule;

import static com.paypalseed.engines.schedule.Fixtures.POLICY;
import static com.paypalseed.engines.schedule.Fixtures.d;
import static com.paypalseed.engines.schedule.Fixtures.receipt;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** TC-AGE-*, PROP-AGE-*: s1 is due 2026-12-09. */
class AgingTest {

    private final Schedule ana = Fixtures.ana();
    private final Allocation nothingPaid = Allocator.allocate(ana, List.of(), POLICY);

    private Aging ageOn(Allocation a, String date) {
        return Aging.of(ana, a, d(date), POLICY);
    }

    @Test
    void tcAge01_dueTodayIsNotPastDue() {
        Aging a = ageOn(nothingPaid, "2026-12-09");
        assertThat(a.dpd()).isZero();
        assertThat(a.bucket()).isEqualTo(Bucket.CURRENT);
    }

    @Test
    void tcAge02_firstDayPastDueIsInGrace() {
        Aging a = ageOn(nothingPaid, "2026-12-10");
        assertThat(a.dpd()).isEqualTo(1);
        assertThat(a.bucket()).isEqualTo(Bucket.DPD_1_7);
        assertThat(a.inGrace()).isTrue();
        assertThat(a.isLate()).isFalse();
        assertThat(a.overdueCents()).isEqualTo(15_000);
    }

    @Test
    void tcAge03_graceExpiresOnDaySix() {
        assertThat(ageOn(nothingPaid, "2026-12-14").graceExpiredSeqs()).isEmpty();
        Aging daySix = ageOn(nothingPaid, "2026-12-15");
        assertThat(daySix.isLate()).isTrue();
        assertThat(daySix.graceExpiredSeqs()).containsExactly(1);
    }

    @ParameterizedTest
    @CsvSource({"7,DPD_1_7", "8,DPD_8_30", "30,DPD_8_30", "31,DPD_31_60", "60,DPD_31_60", "61,DPD_60_PLUS"})
    void tcAge04_bucketBoundaries(int dpd, Bucket expected) {
        Aging a = Aging.of(ana, nothingPaid, d("2026-12-09").plusDays(dpd), POLICY);
        assertThat(a.dpd()).isEqualTo(dpd);
        assertThat(a.bucket()).isEqualTo(expected);
    }

    @Test
    void tcAge05_catchUpCuresTheLoan() {
        Allocation cured = Allocator.allocate(ana, List.of(receipt("2027-01-20", 300)), POLICY);
        assertThat(ageOn(cured, "2027-01-20").bucket()).isEqualTo(Bucket.CURRENT);
    }

    @Test
    void tcAge06_payingOnlyTheOldestMeasuresFromTheNext() {
        Allocation a = Allocator.allocate(ana, List.of(receipt("2027-01-20", 150)), POLICY);
        Aging aging = ageOn(a, "2027-01-20");
        assertThat(aging.oldestUnpaid().orElseThrow().seq()).isEqualTo(2);
        assertThat(aging.dpd()).isEqualTo(11);
    }

    @Test
    void tcAge07_pauseDuringGraceReturnsTheLoanToCurrent() {
        LocalDate asOf = d("2027-02-12"); // s3 due 2027-02-09, dpd 3
        Allocation paid = Allocator.allocate(ana,
                List.of(receipt("2026-12-09", 150), receipt("2027-01-09", 150)), POLICY);
        assertThat(Aging.of(ana, paid, asOf, POLICY).inGrace()).isTrue();

        Schedule v2 = Rescheduler.deferToEnd(ana, paid, 3, asOf);
        Allocation refolded = Allocator.allocate(v2,
                List.of(receipt("2026-12-09", 150), receipt("2027-01-09", 150)), POLICY);
        Aging after = Aging.of(v2, refolded, asOf, POLICY);
        assertThat(after.bucket()).isEqualTo(Bucket.CURRENT);
        assertThat(after.graceExpiredSeqs()).isEmpty();
    }

    @Property
    void propAge01_withoutPaymentsDpdGrowsOneDayAtATime(@ForAll @IntRange(min = 1, max = 400) int day) {
        LocalDate asOf = d("2026-12-09").plusDays(day);
        Aging today = Aging.of(ana, nothingPaid, asOf, POLICY);
        Aging tomorrow = Aging.of(ana, nothingPaid, asOf.plusDays(1), POLICY);
        assertThat(tomorrow.dpd()).isEqualTo(today.dpd() + 1);
    }

    @Property
    void propAge02_bucketIsTotalAndMonotone(@ForAll @IntRange(min = 0, max = 400) int dpd) {
        assertThat(Bucket.of(dpd + 1).ordinal()).isGreaterThanOrEqualTo(Bucket.of(dpd).ordinal());
        assertThat(dpd).isGreaterThanOrEqualTo(Bucket.of(dpd).minDpd());
    }
}
