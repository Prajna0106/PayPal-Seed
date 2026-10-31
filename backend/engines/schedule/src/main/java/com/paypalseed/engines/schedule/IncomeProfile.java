package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.TreeMap;

/**
 * Consented daily income totals over complete months (from the F3 fair check). Holds totals per
 * day only: no counterparties, no transaction detail.
 */
public record IncomeProfile(YearMonth firstMonth, YearMonth lastMonth, Map<LocalDate, Long> dailyIncomeCents) {

    public IncomeProfile {
        if (lastMonth.isBefore(firstMonth)) {
            throw new IllegalArgumentException("lastMonth before firstMonth");
        }
        dailyIncomeCents = new TreeMap<>(dailyIncomeCents);
        for (LocalDate day : dailyIncomeCents.keySet()) {
            YearMonth m = YearMonth.from(day);
            if (m.isBefore(firstMonth) || m.isAfter(lastMonth)) {
                throw new IllegalArgumentException("income on " + day + " is outside the profile months");
            }
        }
    }

    public int monthCount() {
        return (int) (lastMonth.getYear() * 12L + lastMonth.getMonthValue()
                - firstMonth.getYear() * 12L - firstMonth.getMonthValue()) + 1;
    }

    public long incomeOn(LocalDate day) {
        return dailyIncomeCents.getOrDefault(day, 0L);
    }

    public long meanMonthlyIncomeCents() {
        long total = 0;
        for (long cents : dailyIncomeCents.values()) {
            total += cents;
        }
        return total / monthCount();
    }

    /** Mean income on a day of the month (clamped to month end) across the profile's months. */
    public long meanIncomeOnDayOfMonth(int dayOfMonth) {
        long total = 0;
        for (YearMonth m = firstMonth; !m.isAfter(lastMonth); m = m.plusMonths(1)) {
            total += incomeOn(DueDates.dateOf(m, dayOfMonth));
        }
        return total / monthCount();
    }
}
