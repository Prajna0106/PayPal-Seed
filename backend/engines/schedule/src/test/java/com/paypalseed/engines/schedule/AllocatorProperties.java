package com.paypalseed.engines.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.MoneyEvent.OverpaymentRefund;
import com.paypalseed.engines.schedule.MoneyEvent.Receipt;
import com.paypalseed.engines.schedule.MoneyEvent.Reversal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** PROP-ALC-*: allocation invariants over random loans and random money histories. */
class AllocatorProperties {

    /** A random loan plus a random, internally consistent history of receipts and reversals. */
    record Scenario(Schedule schedule, List<MoneyEvent> events, long seed) {
        long netReceived() {
            long net = 0;
            for (MoneyEvent e : events) {
                net += switch (e) {
                    case Receipt r -> r.amountCents();
                    case Reversal v -> -v.amountCents();
                    case OverpaymentRefund f -> -f.amountCents();
                };
            }
            return net;
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Long> principal = Arbitraries.longs().between(3, 50).map(h -> h * 10_000);
        Arbitrary<Integer> count = Arbitraries.integers().between(3, 18);
        Arbitrary<Integer> dueDay = Arbitraries.integers().between(1, 31);
        Arbitrary<Long> seed = Arbitraries.longs();
        return Combinators.combine(principal, count, dueDay, seed).as((p, n, day, s) -> {
            Schedule schedule = ScheduleGenerator.generate(p, n, day, Fixtures.DISBURSED, Fixtures.POLICY);
            return new Scenario(schedule, history(schedule, new Random(s)), s);
        });
    }

    private static List<MoneyEvent> history(Schedule schedule, Random random) {
        List<MoneyEvent> events = new ArrayList<>();
        List<Receipt> receipts = new ArrayList<>();
        long counter = 0;
        LocalDate start = Fixtures.DISBURSED;
        int eventCount = random.nextInt(25);
        for (int k = 0; k < eventCount; k++) {
            LocalDate date = start.plusDays(random.nextInt(600));
            if (!receipts.isEmpty() && random.nextInt(6) == 0) {
                Receipt target = receipts.get(random.nextInt(receipts.size()));
                long already = events.stream()
                        .filter(e -> e instanceof Reversal v && v.receiptId().equals(target.id()))
                        .mapToLong(MoneyEvent::amountCents).sum();
                long room = target.amountCents() - already;
                if (room >= 100) {
                    long amount = (1 + random.nextInt((int) (room / 100))) * 100L;
                    events.add(new Reversal(new UUID(1, ++counter), target.id(), date, amount));
                }
            } else {
                Receipt r = new Receipt(new UUID(2, ++counter), date, (1 + random.nextInt(400)) * 100L);
                receipts.add(r);
                events.add(r);
            }
        }
        return events;
    }

    @Property
    void propAlc01_moneyIsConserved(@ForAll("scenarios") Scenario sc) {
        Allocation a = Allocator.allocate(sc.schedule(), sc.events(), Fixtures.POLICY);
        assertThat(a.paidCents() + a.overpaymentCents()).isEqualTo(sc.netReceived());
    }

    @Property
    void propAlc02_paidNeverExceedsAmount(@ForAll("scenarios") Scenario sc) {
        Allocation a = Allocator.allocate(sc.schedule(), sc.events(), Fixtures.POLICY);
        for (InstalmentAllocation row : a.instalments()) {
            assertThat(row.paidCents()).isBetween(0L, row.amountCents());
            assertThat(row.fullyPaidOn() != null).isEqualTo(row.isFullyPaid());
        }
        assertThat(a.overpaymentCents()).isNotNegative();
    }

    @Property
    void propAlc03_extraPaymentNeverMakesThingsWorse(@ForAll("scenarios") Scenario sc) {
        LocalDate asOf = Fixtures.DISBURSED.plusDays(new Random(sc.seed()).nextInt(700));
        Allocation before = Allocator.allocate(sc.schedule(), sc.events(), Fixtures.POLICY);
        List<MoneyEvent> more = new ArrayList<>(sc.events());
        more.add(new Receipt(new UUID(3, 1), asOf, 5_000));
        Allocation after = Allocator.allocate(sc.schedule(), more, Fixtures.POLICY);

        assertThat(after.outstandingCents()).isLessThanOrEqualTo(before.outstandingCents());
        int dpdBefore = Aging.of(sc.schedule(), before, asOf, Fixtures.POLICY).dpd();
        int dpdAfter = Aging.of(sc.schedule(), after, asOf, Fixtures.POLICY).dpd();
        assertThat(dpdAfter).isLessThanOrEqualTo(dpdBefore);
    }

    @Property
    void propAlc04_inputOrderDoesNotMatter(@ForAll("scenarios") Scenario sc) {
        List<MoneyEvent> shuffled = new ArrayList<>(sc.events());
        Collections.shuffle(shuffled, new Random(sc.seed()));
        assertThat(Allocator.allocate(sc.schedule(), shuffled, Fixtures.POLICY))
                .isEqualTo(Allocator.allocate(sc.schedule(), sc.events(), Fixtures.POLICY));
    }

    @Property
    void propAlc05_outstandingIsPrincipalMinusNetUntilOverpaid(@ForAll("scenarios") Scenario sc) {
        Allocation a = Allocator.allocate(sc.schedule(), sc.events(), Fixtures.POLICY);
        long net = sc.netReceived();
        assertThat(a.outstandingCents()).isEqualTo(Math.max(0, sc.schedule().principalCents() - net));
        assertThat(a.overpaymentCents()).isEqualTo(Math.max(0, net - sc.schedule().principalCents()));
    }
}
