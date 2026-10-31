package com.paypalseed.engines.schedule;

import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.Schedule.ScheduleReason;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Hardship reschedule primitives called by F5 (03-algorithms §10). Each returns a new version and
 * leaves the old one untouched. Every version still sums to the principal, so no money is created
 * or lost; the allocation fold then re-runs against the new version (Fineract's loan reschedule
 * request, minus interest recalculation, which a 0% loan does not need).
 *
 * <p><b>Why the new versions keep a prepaid tail.</b> Re-folding the same money history on a new
 * version must land money where it was. Money that was due is applied oldest first, so it stays put
 * if each instalment keeps the part paid as due money. In-advance money is applied from the last
 * instalment backwards, so the reschedules gather it into one fully prepaid instalment at the very
 * end, which the waterfall fills again. Without this, a pause could quietly move a member's
 * prepayment onto the deferred amount and re-open an instalment they had already paid (found by
 * PROP-RSC-01).
 */
public final class Rescheduler {

    private Rescheduler() {
    }

    /**
     * Option A: move what is still owed on one instalment later by 1..maxMoveDays, keeping instalment
     * order. Money already paid on it stays on the original date.
     */
    public static Schedule moveDate(Schedule s, Allocation a, int seq, int days, LocalDate asOf, RepaymentPolicy policy) {
        Instalment target = s.instalment(seq);
        InstalmentAllocation targetAlloc = requireUnpaid(a, seq);
        if (days < 1 || days > policy.maxMoveDays()) {
            throw ScheduleErrors.violation(ScheduleErrors.MOVE_OUT_OF_RANGE,
                    "Move must be 1.." + policy.maxMoveDays() + " days", Map.of("days", days));
        }
        LocalDate newDue = target.dueDate().plusDays(days);
        if (!newDue.isAfter(asOf)) {
            throw ScheduleErrors.violation(ScheduleErrors.MOVE_LANDS_IN_PAST, "New due date " + newDue + " is not after " + asOf);
        }
        if (s.find(seq + 1).filter(next -> !newDue.isBefore(next.dueDate())).isPresent()) {
            throw ScheduleErrors.violation(ScheduleErrors.MOVE_CROSSES_NEXT, "New due date " + newDue + " reaches the next instalment");
        }
        Rows rows = new Rows();
        long inAdvance = 0;
        for (Instalment inst : s.instalments()) {
            InstalmentAllocation alloc = a.of(inst.seq());
            inAdvance += alloc.paidInAdvanceCents();
            if (inst.seq() == seq) {
                rows.add(inst.dueDate(), alloc.paidCents() - alloc.paidInAdvanceCents());
                rows.add(newDue, targetAlloc.remainingCents());
            } else {
                rows.add(inst.dueDate(), inst.amountCents() - alloc.paidInAdvanceCents());
            }
        }
        rows.addTail(inAdvance, s.dueDay());
        return new Schedule(s.version() + 1, ScheduleReason.HARDSHIP_MOVE_DATE, s.principalCents(), s.dueDay(), rows.number());
    }

    /**
     * Option B: a one-month pause. What is still owed on one instalment moves to the month after the
     * last instalment still owed (and never to a date on or before today); in-advance money moves to
     * a prepaid tail after it.
     */
    public static Schedule deferToEnd(Schedule s, Allocation a, int seq, LocalDate asOf) {
        s.instalment(seq);
        InstalmentAllocation target = requireUnpaid(a, seq);
        Rows rows = new Rows();
        long inAdvance = 0;
        LocalDate lastOwed = s.instalment(seq).dueDate();
        for (Instalment inst : s.instalments()) {
            InstalmentAllocation alloc = a.of(inst.seq());
            inAdvance += alloc.paidInAdvanceCents();
            long dueMoney = alloc.paidCents() - alloc.paidInAdvanceCents();
            long keep = inst.seq() == seq ? dueMoney : inst.amountCents() - alloc.paidInAdvanceCents();
            rows.add(inst.dueDate(), keep);
            if (inst.seq() != seq && alloc.remainingCents() > 0 && inst.dueDate().isAfter(lastOwed)) {
                lastOwed = inst.dueDate();
            }
        }
        YearMonth afterLastOwed = YearMonth.from(lastOwed).plusMonths(1);
        YearMonth afterToday = DueDates.firstMonthAfter(asOf, s.dueDay());
        LocalDate deferredDue = DueDates.dateOf(afterLastOwed.isAfter(afterToday) ? afterLastOwed : afterToday, s.dueDay());
        rows.add(deferredDue, target.remainingCents());
        rows.addTail(inAdvance, s.dueDay());
        return new Schedule(s.version() + 1, ScheduleReason.HARDSHIP_PAUSE, s.principalCents(), s.dueDay(), rows.number());
    }

    /**
     * Option C: a smaller, longer plan. The outstanding balance is spread evenly over the unpaid
     * instalment count plus {@code extraMonths} (never below {@code minInstalmentCents}), starting
     * with the first due day after today and after every instalment that keeps money.
     */
    public static Schedule reamortize(Schedule s, Allocation a, int extraMonths, LocalDate asOf, RepaymentPolicy policy) {
        if (extraMonths < 1 || extraMonths > policy.maxExtraMonths()) {
            throw ScheduleErrors.violation(ScheduleErrors.EXTRA_MONTHS_OUT_OF_RANGE,
                    "Extra months must be 1.." + policy.maxExtraMonths(), Map.of("extraMonths", extraMonths));
        }
        long outstanding = a.outstandingCents();
        if (outstanding == 0) {
            throw ScheduleErrors.violation(ScheduleErrors.NOTHING_OUTSTANDING, "Nothing left to reschedule");
        }
        Rows rows = new Rows();
        long inAdvance = 0;
        int unpaidCount = 0;
        LocalDate lastKept = asOf;
        for (Instalment inst : s.instalments()) {
            InstalmentAllocation alloc = a.of(inst.seq());
            inAdvance += alloc.paidInAdvanceCents();
            if (alloc.remainingCents() > 0) {
                unpaidCount++;
            }
            long dueMoney = alloc.paidCents() - alloc.paidInAdvanceCents();
            if (dueMoney > 0) {
                rows.add(inst.dueDate(), dueMoney);
                lastKept = inst.dueDate().isAfter(lastKept) ? inst.dueDate() : lastKept;
            }
        }
        long affordable = outstanding / policy.minInstalmentCents();
        int count = (int) Math.max(1, Math.min(unpaidCount + (long) extraMonths, affordable));
        if (count <= unpaidCount) {
            throw ScheduleErrors.violation(ScheduleErrors.REAMORTIZE_NOT_BENEFICIAL,
                    "The $" + policy.minInstalmentCents() / 100 + " minimum leaves no room for a longer plan",
                    Map.of("instalments", count, "unpaidInstalments", unpaidCount));
        }
        long[] amounts = ScheduleGenerator.split(outstanding, count, policy);
        YearMonth first = DueDates.firstMonthAfter(lastKept, s.dueDay());
        for (Instalment inst : ScheduleGenerator.monthly(first, s.dueDay(), amounts, 1)) {
            rows.add(inst.dueDate(), inst.amountCents());
        }
        rows.addTail(inAdvance, s.dueDay());
        return new Schedule(s.version() + 1, ScheduleReason.HARDSHIP_REAMORTIZE, s.principalCents(), s.dueDay(), rows.number());
    }

    private static InstalmentAllocation requireUnpaid(Allocation a, int seq) {
        InstalmentAllocation alloc = a.of(seq);
        if (alloc.remainingCents() == 0) {
            throw ScheduleErrors.violation(ScheduleErrors.INSTALMENT_ALREADY_PAID, "Instalment " + seq + " is already paid");
        }
        return alloc;
    }

    /** Unnumbered (due date, amount) rows; zero amounts are dropped. Sorting is stable, so ties keep insertion order. */
    private static final class Rows {
        private final List<Instalment> rows = new ArrayList<>();

        void add(LocalDate due, long amountCents) {
            if (amountCents > 0) {
                rows.add(new Instalment(rows.size() + 1, due, amountCents));
            }
        }

        /** One fully prepaid instalment in the month after the latest row, so in-advance money refills it. */
        void addTail(long amountCents, int dueDay) {
            if (amountCents > 0) {
                LocalDate latest = rows.stream().map(Instalment::dueDate).max(Comparator.naturalOrder()).orElseThrow();
                add(DueDates.dateOf(YearMonth.from(latest).plusMonths(1), dueDay), amountCents);
            }
        }

        List<Instalment> number() {
            List<Instalment> sorted = new ArrayList<>(rows);
            sorted.sort(Comparator.comparing(Instalment::dueDate));
            List<Instalment> out = new ArrayList<>(sorted.size());
            for (int i = 0; i < sorted.size(); i++) {
                out.add(new Instalment(i + 1, sorted.get(i).dueDate(), sorted.get(i).amountCents()));
            }
            return out;
        }
    }
}
