package com.paypalseed.engines.schedule;

import com.paypalseed.engines.schedule.RepaymentPolicy.RemainderPlacement;
import com.paypalseed.engines.schedule.Schedule.ScheduleReason;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates equal 0% whole-dollar instalments (03-algorithms §2).
 *
 * <p>The split gives every instalment ⌊P/n⌋ dollars and spreads the remainder one dollar at a time.
 * Its largest instalment is ⌈P/n⌉, the smallest any whole-dollar split can achieve, which is exactly
 * the figure the F2 affordability rule caps. Fineract's "installment amount in multiples of" puts
 * the whole remainder on the last instalment instead; that can raise the last one by up to n−1
 * dollars.
 */
public final class ScheduleGenerator {

    private static final long CENTS = 100;

    private ScheduleGenerator() {
    }

    public static Schedule generate(
            long principalCents, int instalmentCount, int dueDay, LocalDate disbursedOn, RepaymentPolicy policy) {
        DueDates.requireDueDay(dueDay);
        YearMonth first = DueDates.firstMonthOnOrAfter(disbursedOn.plusDays(policy.minFirstDueLeadDays()), dueDay);
        List<Instalment> instalments = monthly(first, dueDay, split(principalCents, instalmentCount, policy), 1);
        return new Schedule(1, ScheduleReason.ORIGINAL, principalCents, dueDay, instalments);
    }

    /** Splits a whole-dollar total into {@code count} whole-dollar parts differing by at most $1. */
    public static long[] split(long totalCents, int count, RepaymentPolicy policy) {
        if (totalCents <= 0 || totalCents % CENTS != 0) {
            throw ScheduleErrors.violation(ScheduleErrors.PRINCIPAL_NOT_WHOLE_DOLLARS,
                    "Amount must be a positive whole-dollar amount", Map.of("amountCents", totalCents));
        }
        long dollars = totalCents / CENTS;
        if (count < 1 || count > policy.maxInstalments() || count > dollars) {
            throw ScheduleErrors.violation(ScheduleErrors.INVALID_TERM,
                    "Cannot split $" + dollars + " into " + count + " whole-dollar instalments",
                    Map.of("instalments", count, "maxInstalments", policy.maxInstalments()));
        }
        long base = dollars / count;
        long remainder = dollars % count;
        long[] parts = new long[count];
        for (int k = 0; k < count; k++) {
            boolean getsExtra = policy.remainderPlacement() == RemainderPlacement.BACK
                    ? k >= count - remainder
                    : k < remainder;
            parts[k] = (base + (getsExtra ? 1 : 0)) * CENTS;
        }
        return parts;
    }

    static List<Instalment> monthly(YearMonth firstMonth, int dueDay, long[] amounts, int firstSeq) {
        List<Instalment> out = new ArrayList<>(amounts.length);
        for (int k = 0; k < amounts.length; k++) {
            out.add(new Instalment(firstSeq + k, DueDates.dateOf(firstMonth.plusMonths(k), dueDay), amounts[k]));
        }
        return out;
    }
}
