package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Monthly due-date arithmetic (03-algorithms §3). Each date is derived from an anchor month plus k,
 * never from the previous due date, so a 31st due day gives 31 Jan, 28 Feb, 31 Mar without drifting.
 */
public final class DueDates {

    private DueDates() {
    }

    /** The due day in a month, clamped to the month's last day. */
    public static LocalDate dateOf(YearMonth month, int dueDay) {
        requireDueDay(dueDay);
        return month.atDay(Math.min(dueDay, month.lengthOfMonth()));
    }

    public static YearMonth firstMonthOnOrAfter(LocalDate earliest, int dueDay) {
        YearMonth month = YearMonth.from(earliest);
        while (dateOf(month, dueDay).isBefore(earliest)) {
            month = month.plusMonths(1);
        }
        return month;
    }

    public static YearMonth firstMonthAfter(LocalDate date, int dueDay) {
        return firstMonthOnOrAfter(date.plusDays(1), dueDay);
    }

    public static void requireDueDay(int dueDay) {
        if (dueDay < 1 || dueDay > 31) {
            throw ScheduleErrors.violation(ScheduleErrors.INVALID_DUE_DAY, "Due day " + dueDay + " is not 1..31");
        }
    }
}
