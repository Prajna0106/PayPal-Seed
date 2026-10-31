package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Picks the date of the single automatic retry after a failed autopay charge (03-algorithms §8).
 *
 * <p>The retry lands inside days past due {@code retryMinDpd..retryMaxDpd}, at least
 * {@code retryMinGapDays} after the failure, so its result is known before grace ends. With an
 * income profile it picks the day after money usually lands. There is never a second automatic
 * retry: repeated charges against an empty balance can trigger the member's bank fees.
 */
public final class RetryPlanner {

    private RetryPlanner() {
    }

    public static Optional<LocalDate> retryDate(
            LocalDate dueDate, LocalDate failedOn, Optional<IncomeProfile> profile, RepaymentPolicy policy) {
        LocalDate earliest = max(failedOn.plusDays(policy.retryMinGapDays()), dueDate.plusDays(policy.retryMinDpd()));
        LocalDate latest = dueDate.plusDays(policy.retryMaxDpd());
        if (earliest.isAfter(latest)) {
            return Optional.empty();
        }
        if (profile.isEmpty() || profile.get().meanMonthlyIncomeCents() == 0) {
            LocalDate middle = dueDate.plusDays((policy.retryMinDpd() + policy.retryMaxDpd()) / 2);
            return Optional.of(middle.isBefore(earliest) ? earliest : middle);
        }
        LocalDate best = earliest;
        long bestIncome = -1;
        for (LocalDate t = earliest; !t.isAfter(latest); t = t.plusDays(1)) {
            long income = profile.get().meanIncomeOnDayOfMonth(t.minusDays(1).getDayOfMonth());
            if (income > bestIncome) {
                bestIncome = income;
                best = t;
            }
        }
        return Optional.of(best);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
