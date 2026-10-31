package com.paypalseed.engines.schedule;

import static com.paypalseed.engines.schedule.Fixtures.POLICY;
import static com.paypalseed.engines.schedule.Fixtures.d;
import static com.paypalseed.engines.schedule.Fixtures.receipt;
import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engines.schedule.TimerPlanner.Kind;
import com.paypalseed.engines.schedule.TimerPlanner.LoanSnapshot;
import com.paypalseed.engines.schedule.TimerPlanner.PlannedRetry;
import com.paypalseed.engines.schedule.TimerPlanner.Timer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** TC-TMR-*. */
class TimerPlannerTest {

    private final Schedule ana = Fixtures.ana();

    @Test
    void tcTmr01_newAutopayLoanHasReminderWatchdogNudgeExpiryAndBoundaries() {
        Allocation none = Allocator.allocate(ana, List.of(), POLICY);
        Set<Timer> timers = TimerPlanner.plan(new LoanSnapshot(true, true, ana, none, List.of()), POLICY);

        assertThat(timers).contains(
                new Timer(Kind.REMIND_DUE_SOON, "v1:s1", d("2026-12-06")),
                new Timer(Kind.WATCHDOG, "v1:s1", d("2026-12-10")),
                new Timer(Kind.GRACE_NUDGE, "v1:s1", d("2026-12-12")),
                new Timer(Kind.GRACE_EXPIRY, "v1:s1", d("2026-12-15")),
                new Timer(Kind.BUCKET_BOUNDARY, "v1:s1:dpd1", d("2026-12-10")),
                new Timer(Kind.BUCKET_BOUNDARY, "v1:s1:dpd8", d("2026-12-17")),
                new Timer(Kind.BUCKET_BOUNDARY, "v1:s1:dpd31", d("2027-01-09")),
                new Timer(Kind.BUCKET_BOUNDARY, "v1:s1:dpd61", d("2027-02-08")));
        assertThat(timers).hasSize(8 * 4 + 4);
    }

    @Test
    void tcTmr02_planningIsDeterministic() {
        Allocation a = Allocator.allocate(ana, List.of(receipt("2026-12-09", 150)), POLICY);
        LoanSnapshot loan = new LoanSnapshot(true, true, ana, a, List.of());
        assertThat(TimerPlanner.plan(loan, POLICY)).isEqualTo(TimerPlanner.plan(loan, POLICY));
    }

    @Test
    void tcTmr03_inactiveLoanHasNoTimers() {
        Allocation a = Allocator.allocate(ana, List.of(), POLICY);
        assertThat(TimerPlanner.plan(new LoanSnapshot(false, true, ana, a, List.of()), POLICY)).isEmpty();
    }

    @Test
    void tcTmr04_manualLoanHasNoWatchdog_paidInstalmentsHaveNoTimers_retriesArePlanned() {
        Allocation a = Allocator.allocate(ana, List.of(receipt("2026-12-09", 150)), POLICY);
        Set<Timer> timers = TimerPlanner.plan(
                new LoanSnapshot(true, false, ana, a, List.of(new PlannedRetry("attempt:42", d("2027-01-12")))), POLICY);

        assertThat(timers).noneMatch(t -> t.kind() == Kind.WATCHDOG);
        assertThat(timers).noneMatch(t -> t.ref().startsWith("v1:s1"));
        assertThat(timers).contains(new Timer(Kind.BUCKET_BOUNDARY, "v1:s2:dpd1", d("2027-01-10")));
        assertThat(timers).contains(new Timer(Kind.RETRY_CAPTURE, "attempt:42", d("2027-01-12")));
    }
}
