package com.paypalseed.engines.schedule;

import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Status of one instalment on a business date. Derived on read, never stored (02-design §3.3). */
public enum InstalmentStatus {
    /** Due more than {@code dueWindowDays} ahead, nothing paid yet. */
    SCHEDULED,
    /** Due within the window or today, nothing paid yet. */
    DUE,
    /** Not yet past due, part paid. */
    PARTIALLY_PAID,
    /** 1..graceDays past due with money left. */
    IN_GRACE,
    /** Past the grace period with money left. */
    LATE,
    /** Fully paid no later than the end of its grace period. */
    PAID,
    /** Fully paid after its grace period ran out. */
    PAID_LATE,
    /** Fully paid by in-advance money while still in the future. */
    PREPAID;

    public static InstalmentStatus of(Instalment inst, InstalmentAllocation a, LocalDate asOf, RepaymentPolicy policy) {
        long daysPast = ChronoUnit.DAYS.between(inst.dueDate(), asOf);
        if (a.isFullyPaid()) {
            if (daysPast < -policy.dueWindowDays()) {
                return PREPAID;
            }
            long paidDaysPast = ChronoUnit.DAYS.between(inst.dueDate(), a.fullyPaidOn());
            return paidDaysPast > policy.graceDays() ? PAID_LATE : PAID;
        }
        if (daysPast > policy.graceDays()) {
            return LATE;
        }
        if (daysPast >= 1) {
            return IN_GRACE;
        }
        if (a.paidCents() > 0) {
            return PARTIALLY_PAID;
        }
        return daysPast >= -policy.dueWindowDays() ? DUE : SCHEDULED;
    }
}
