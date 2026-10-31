package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The dates on which a loan's state can next change (ADR 0007). The result is a pure function of
 * the loan's state; the lending module stores it as durable timers and close of business fires the
 * ones that are due. A timer's {@code ref} names what it is about, so a timer that has already fired
 * is never planned again for the same instalment version.
 */
public final class TimerPlanner {

    public enum Kind {
        /** "Your payment is in N days." Fires only while the instalment is still unpaid and not yet due. */
        REMIND_DUE_SOON,
        /** Autopay: check PayPal the day after a due date if no payment or failure arrived. */
        WATCHDOG,
        /** Catch-up nudge on a fixed day of grace. */
        GRACE_NUDGE,
        /** Grace runs out: re-age, record InstalmentMissed. */
        GRACE_EXPIRY,
        /** The oldest unpaid instalment crosses a delinquency bucket boundary: re-age. */
        BUCKET_BOUNDARY,
        /** The single income-aware retry of a failed autopay charge. */
        RETRY_CAPTURE
    }

    public record Timer(Kind kind, String ref, LocalDate fireOn) {
        public Timer {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(fireOn, "fireOn");
        }
    }

    public record PlannedRetry(String attemptRef, LocalDate retryOn) {
    }

    public record LoanSnapshot(boolean active, boolean autopay, Schedule schedule, Allocation allocation,
                               List<PlannedRetry> plannedRetries) {
        public LoanSnapshot {
            plannedRetries = List.copyOf(plannedRetries);
        }
    }

    private TimerPlanner() {
    }

    public static Set<Timer> plan(LoanSnapshot loan, RepaymentPolicy policy) {
        Set<Timer> timers = new LinkedHashSet<>();
        if (!loan.active()) {
            return timers;
        }
        Schedule schedule = loan.schedule();
        Instalment oldestUnpaid = null;
        for (Instalment inst : schedule.instalments()) {
            if (loan.allocation().of(inst.seq()).remainingCents() == 0) {
                continue;
            }
            if (oldestUnpaid == null) {
                oldestUnpaid = inst;
            }
            String ref = inst.ref(schedule.version());
            LocalDate due = inst.dueDate();
            timers.add(new Timer(Kind.REMIND_DUE_SOON, ref, due.minusDays(policy.reminderLeadDays())));
            if (loan.autopay()) {
                timers.add(new Timer(Kind.WATCHDOG, ref, due.plusDays(1)));
            }
            timers.add(new Timer(Kind.GRACE_NUDGE, ref, due.plusDays(policy.graceNudgeDpd())));
            timers.add(new Timer(Kind.GRACE_EXPIRY, ref, due.plusDays(policy.graceDays() + 1L)));
        }
        if (oldestUnpaid != null) {
            for (Bucket bucket : Bucket.values()) {
                if (bucket.isDelinquent()) {
                    timers.add(new Timer(Kind.BUCKET_BOUNDARY,
                            oldestUnpaid.ref(schedule.version()) + ":dpd" + bucket.minDpd(),
                            oldestUnpaid.dueDate().plusDays(bucket.minDpd())));
                }
            }
        }
        for (PlannedRetry retry : loan.plannedRetries()) {
            timers.add(new Timer(Kind.RETRY_CAPTURE, retry.attemptRef(), retry.retryOn()));
        }
        return timers;
    }
}
