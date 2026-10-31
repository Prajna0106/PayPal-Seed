package com.paypalseed.engines.schedule;

import static com.paypalseed.engines.schedule.Fixtures.POLICY;
import static com.paypalseed.engines.schedule.Fixtures.d;
import static com.paypalseed.engines.schedule.Fixtures.receipt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.MoneyEvent.Receipt;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** TC-RSC-*, PROP-RSC-01. */
class ReschedulerTest {

    private final Schedule ana = Fixtures.ana();
    private final List<MoneyEvent> twoPaid = List.of(receipt("2026-12-09", 150), receipt("2027-01-09", 150));
    private final Allocation paidTwo = Allocator.allocate(ana, twoPaid, POLICY);

    private static ErrorCode codeOf(Throwable t) {
        return ((DomainException) t).errorCode();
    }

    @Test
    void tcRsc01_moveDateRules() {
        LocalDate asOf = d("2027-03-01");
        Schedule moved = Rescheduler.moveDate(ana, paidTwo, 4, 10, asOf, POLICY);
        assertThat(moved.instalment(4).dueDate()).isEqualTo(d("2027-03-19"));
        assertThat(moved.reason()).isEqualTo(Schedule.ScheduleReason.HARDSHIP_MOVE_DATE);

        assertThatThrownBy(() -> Rescheduler.moveDate(ana, paidTwo, 4, 15, asOf, POLICY))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ScheduleErrors.MOVE_OUT_OF_RANGE));
        assertThatThrownBy(() -> Rescheduler.moveDate(ana, paidTwo, 1, 5, asOf, POLICY))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ScheduleErrors.INSTALMENT_ALREADY_PAID));
        Schedule tight = new Schedule(1, Schedule.ScheduleReason.ORIGINAL, 30_000, 9, List.of(
                new Instalment(1, d("2027-03-09"), 10_000), new Instalment(2, d("2027-03-20"), 10_000),
                new Instalment(3, d("2027-04-09"), 10_000)));
        Allocation none = Allocator.allocate(tight, List.of(), POLICY);
        assertThatThrownBy(() -> Rescheduler.moveDate(tight, none, 1, 12, asOf, POLICY))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ScheduleErrors.MOVE_CROSSES_NEXT));
    }

    @Test
    void tcRsc02_pauseMovesTheInstalmentToANewLastMonth() {
        Schedule v2 = Rescheduler.deferToEnd(ana, paidTwo, 3, d("2027-02-12"));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.size()).isEqualTo(8);
        assertThat(v2.last().dueDate()).isEqualTo(d("2027-08-09"));
        assertThat(v2.instalments()).noneMatch(i -> i.dueDate().equals(d("2027-02-09")));
        assertThat(ana.instalment(3).dueDate()).isEqualTo(d("2027-02-09")); // version 1 untouched

        ScheduleDiff diff = ScheduleDiff.between(ana, v2);
        assertThat(diff.moves()).singleElement().satisfies(m -> {
            assertThat(m.from().dueDate()).isEqualTo(d("2027-02-09"));
            assertThat(m.to().dueDate()).isEqualTo(d("2027-08-09"));
        });
    }

    @Test
    void tcRsc03_pauseOfAPartlyPaidInstalmentSplitsIt() {
        List<MoneyEvent> events = new ArrayList<>(twoPaid);
        events.add(receipt("2027-02-09", 50));
        Allocation a = Allocator.allocate(ana, events, POLICY);
        Schedule v2 = Rescheduler.deferToEnd(ana, a, 3, d("2027-02-12"));
        assertThat(v2.size()).isEqualTo(9);
        assertThat(v2.instalment(3).amountCents()).isEqualTo(5_000);
        assertThat(v2.last().amountCents()).isEqualTo(10_000);
    }

    @Test
    void tcRsc04_reamortizeSpreadsTheRestOverMoreMonths() {
        LocalDate asOf = d("2027-01-20"); // s1, s2 paid; s3..s8 ($900) unpaid
        Schedule v2 = Rescheduler.reamortize(ana, paidTwo, 6, asOf, POLICY);
        assertThat(v2.size()).isEqualTo(2 + 12);
        assertThat(v2.instalments().subList(2, 14)).allSatisfy(i -> assertThat(i.amountCents()).isEqualTo(7_500));
        assertThat(v2.instalment(3).dueDate()).isEqualTo(d("2027-02-09"));
    }

    @Test
    void tcRsc05_reamortizeRespectsTheMinimumInstalment() {
        List<MoneyEvent> mostlyPaid = List.of(receipt("2026-12-09", 1_100));
        Allocation a = Allocator.allocate(ana, mostlyPaid, POLICY); // $100 left; $950 paid in advance
        Schedule v2 = Rescheduler.reamortize(ana, a, 6, d("2026-12-20"), POLICY);
        Allocation refolded = Allocator.allocate(v2, mostlyPaid, POLICY);

        assertThat(refolded.outstandingCents()).isEqualTo(10_000);
        // s1 kept; $100 over 4 × $25 (the minimum caps 1 + 6 months at 4); the $950 prepaid becomes the tail
        assertThat(v2.instalments()).extracting(Instalment::amountCents)
                .containsExactly(15_000L, 2_500L, 2_500L, 2_500L, 2_500L, 95_000L);
        assertThat(refolded.of(2).remainingCents()).isEqualTo(2_500); // next payment is $25, not $150
        assertThat(refolded.of(6).isFullyPaid()).isTrue();
    }

    @Test
    void reamortizeWithNothingToGainIsRejected() {
        Schedule small = ScheduleGenerator.generate(5_000, 2, 9, Fixtures.DISBURSED, POLICY);
        Allocation none = Allocator.allocate(small, List.of(), POLICY);
        assertThatThrownBy(() -> Rescheduler.reamortize(small, none, 6, d("2026-11-20"), POLICY))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ScheduleErrors.REAMORTIZE_NOT_BENEFICIAL));
    }

    /**
     * PROP-RSC-01: after any primitive, re-folding the same history on the new version conserves
     * money, never leaves the member more overdue, and keeps in-advance money in advance.
     */
    @Property(tries = 500)
    void propRsc01_reschedulingNeverLosesMoneyOrUnpaysThePast(
            @ForAll @IntRange(min = 0, max = 2) int primitive,
            @ForAll @IntRange(min = 20, max = 300) int daysIn,
            @ForAll long seed) {
        Random random = new Random(seed);
        LocalDate asOf = d("2026-12-09").plusDays(daysIn);
        List<MoneyEvent> events = new ArrayList<>();
        for (int k = 0; k < random.nextInt(8); k++) {
            events.add(new Receipt(new UUID(9, k), d("2026-11-20").plusDays(random.nextInt(daysIn + 19)),
                    (1 + random.nextInt(300)) * 100L));
        }
        Allocation before = Allocator.allocate(ana, events, POLICY);
        if (before.outstandingCents() == 0) {
            return;
        }
        int unpaidSeq = before.instalments().stream().filter(r -> r.remainingCents() > 0)
                .map(InstalmentAllocation::seq).findFirst().orElseThrow();
        Schedule after;
        try {
            after = switch (primitive) {
                case 0 -> Rescheduler.moveDate(ana, before, unpaidSeq, 1 + random.nextInt(14), asOf, POLICY);
                case 1 -> Rescheduler.deferToEnd(ana, before, unpaidSeq, asOf);
                default -> Rescheduler.reamortize(ana, before, 1 + random.nextInt(6), asOf, POLICY);
            };
        } catch (DomainException refused) {
            return; // a rule refused this particular request; nothing to check
        }
        Allocation refolded = Allocator.allocate(after, events, POLICY);

        assertThat(after.principalCents()).isEqualTo(ana.principalCents());
        assertThat(refolded.outstandingCents()).isEqualTo(before.outstandingCents());
        assertThat(refolded.overpaymentCents()).isEqualTo(before.overpaymentCents());
        Aging agingBefore = Aging.of(ana, before, asOf, POLICY);
        Aging agingAfter = Aging.of(after, refolded, asOf, POLICY);
        assertThat(agingAfter.overdueCents()).isLessThanOrEqualTo(agingBefore.overdueCents());
        assertThat(agingAfter.dpd()).isLessThanOrEqualTo(agingBefore.dpd());
        // in-advance money stays in advance: the member never owes sooner because of a reschedule
        long prepaidBefore = before.instalments().stream().mapToLong(InstalmentAllocation::paidInAdvanceCents).sum();
        long prepaidAfter = refolded.instalments().stream().mapToLong(InstalmentAllocation::paidInAdvanceCents).sum();
        assertThat(prepaidAfter).isEqualTo(prepaidBefore);
    }
}
