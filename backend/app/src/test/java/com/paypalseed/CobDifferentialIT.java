package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engines.schedule.Aging;
import com.paypalseed.engines.schedule.Allocation;
import com.paypalseed.engines.schedule.Allocator;
import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.engines.schedule.MoneyEvent;
import com.paypalseed.engines.schedule.RepaymentPolicy;
import com.paypalseed.engines.schedule.Schedule;
import com.paypalseed.engines.schedule.ScheduleGenerator;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.application.usecases.CloseOfBusiness;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.lending.infrastructure.persistence.ProjectionRepository;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * TC-COB-07, as a differential test against an oracle: random loans and random payments over five
 * months, closed day by day through the real database, then compared with a pure-engine replay
 * that recomputes every loan from scratch every day. Any timer that fails to fire, fires on the
 * wrong day or is planned wrongly shows up as a different transition list. The SCAN-mode subclass
 * runs the same check without the timer index.
 */
class CobDifferentialIT extends IntegrationTest {

    private static final RepaymentPolicy POLICY = RepaymentPolicy.V1;
    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final int DAYS = 150;
    private static final int LOANS = 12;

    @Autowired
    CloseOfBusiness cob;
    @Autowired
    RepaymentService repayments;
    @Autowired
    ProjectionRepository projections;

    record Payment(LocalDate on, long cents) {
    }

    record SimLoan(UUID id, Schedule schedule, List<Payment> payments) {
    }

    @Test
    void closeOfBusinessMatchesTheOracleDayByDay() {
        Random random = new Random(20261112L);
        List<SimLoan> loans = new ArrayList<>();
        clock.set(START);
        for (int i = 0; i < LOANS; i++) {
            LocalDate disbursed = START.plusDays(random.nextInt(20));
            long dollars = (3 + random.nextInt(18)) * 100L;
            int months = 3 + random.nextInt(6);
            int dueDay = 1 + random.nextInt(31);
            clock.set(disbursed);
            LoanSummary loan = disburse(UUID.randomUUID(), dollars, months, dueDay, disbursed);
            Schedule schedule = ScheduleGenerator.generate(dollars * 100, months, dueDay, disbursed, POLICY);
            List<Payment> payments = new ArrayList<>();
            for (int k = 0; k < 2 + random.nextInt(8); k++) {
                LocalDate on = disbursed.plusDays(1 + random.nextInt(DAYS - 25));
                payments.add(new Payment(on, (1 + random.nextInt((int) (dollars / months) + 60)) * 100L));
            }
            loans.add(new SimLoan(loan.loanId(), schedule, payments));
        }

        for (int d = 0; d < DAYS; d++) {
            LocalDate day = START.plusDays(d);
            clock.set(day);
            cob.catchUp(day, "differential");
            for (SimLoan loan : loans) {
                int n = 0;
                for (Payment p : loan.payments()) {
                    if (p.on().equals(day)) {
                        repayments.recordReceipt(new RepaymentService.Receipt(loan.id(), loan.id() + "-" + day + "-" + n++,
                                Source.CHECKOUT, p.cents(), 0, Instant.now(), null, "differential"));
                    }
                }
            }
        }
        settle();

        for (SimLoan loan : loans) {
            Oracle expected = oracle(loan);
            List<String> actual = projections.transitions(loan.id()).stream()
                    .map(t -> t.asOf() + " " + t.fromBucket() + ">" + t.toBucket())
                    .toList();
            assertThat(actual).as("bucket transitions of loan %s", loan.id()).isEqualTo(expected.transitions());
            assertThat(projections.missedCount(loan.id())).as("missed instalments of loan %s", loan.id())
                    .isEqualTo(expected.missed().size());
            assertIntegrity(loan.id());
        }
    }

    record Oracle(List<String> transitions, Set<Integer> missed) {
    }

    /** Recomputes the loan from scratch each day: close of business first, then that day's payments. */
    private static Oracle oracle(SimLoan loan) {
        List<String> transitions = new ArrayList<>();
        Set<Integer> missed = new LinkedHashSet<>();
        Bucket bucket = Bucket.CURRENT;
        boolean active = true;
        List<MoneyEvent> events = new ArrayList<>();
        Map<LocalDate, List<Payment>> byDay = new HashMap<>();
        loan.payments().forEach(p -> byDay.computeIfAbsent(p.on(), k -> new ArrayList<>()).add(p));
        long seq = 0;
        LocalDate firstDue = loan.schedule().instalments().get(0).dueDate();
        for (int d = 0; d < DAYS; d++) {
            LocalDate day = START.plusDays(d);
            if (day.isBefore(firstDue.minusMonths(2))) {
                continue;
            }
            // close of business for the day, then each payment of the day, each a recalculation point
            List<List<Payment>> points = new ArrayList<>();
            points.add(List.of());
            for (Payment p : byDay.getOrDefault(day, List.of())) {
                points.add(List.of(p));
            }
            for (List<Payment> point : points) {
                for (Payment p : point) {
                    events.add(new MoneyEvent.Receipt(new UUID(7, ++seq), day, p.cents()));
                }
                Allocation a = Allocator.allocate(loan.schedule(), events, POLICY);
                Aging aging = Aging.of(loan.schedule(), a, day, POLICY);
                if (active && a.outstandingCents() == 0) {
                    active = false;
                }
                Bucket next = active ? aging.bucket() : Bucket.CURRENT;
                if (active) {
                    missed.addAll(aging.graceExpiredSeqs());
                }
                if (next != bucket) {
                    transitions.add(day + " " + bucket + ">" + next);
                    bucket = next;
                }
            }
        }
        return new Oracle(transitions, missed);
    }
}
