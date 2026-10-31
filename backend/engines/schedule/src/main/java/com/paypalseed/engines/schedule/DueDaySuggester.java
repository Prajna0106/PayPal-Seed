package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Suggests a due day just after the member's money usually arrives (03-algorithms §4).
 *
 * <p>Each candidate day d is scored by the mean income received in the 7 days before it. The
 * window is on real dates, so it wraps across month ends for free. A circular mean of income days
 * was rejected: biweekly pay on the 1st and 15th averages to the 8th, when no money arrives.
 */
public final class DueDaySuggester {

    public enum Basis {
        INCOME_PATTERN,
        STATED_PAYDAY,
        DEFAULT
    }

    public record DueDaySuggestion(int dueDay, Basis basis, long typicalIncomeBeforeCents, String reason) {
    }

    static final int MAX_SUGGESTED_DAY = 28;
    static final int MIN_MONTHS = 3;
    static final int WINDOW_DAYS = 7;
    /** Share of monthly income that must land in the window for the pattern to count. */
    static final double MIN_STRENGTH = 0.35;

    private DueDaySuggester() {
    }

    public static DueDaySuggestion suggest(Optional<IncomeProfile> profile, OptionalInt statedPayday) {
        if (profile.isPresent() && profile.get().monthCount() >= MIN_MONTHS) {
            IncomeProfile p = profile.get();
            int bestDay = 1;
            long bestScore = -1;
            for (int d = 1; d <= MAX_SUGGESTED_DAY; d++) {
                long score = windowScore(p, d);
                if (score > bestScore) {
                    bestScore = score;
                    bestDay = d;
                }
            }
            long monthly = p.meanMonthlyIncomeCents();
            if (monthly > 0 && (double) bestScore / monthly >= MIN_STRENGTH) {
                return new DueDaySuggestion(bestDay, Basis.INCOME_PATTERN, bestScore,
                        "On average $" + bestScore / 100 + " of your income arrives in the week before the "
                                + ordinal(bestDay) + ".");
            }
        }
        if (statedPayday.isPresent()) {
            int dayAfter = statedPayday.getAsInt() + 1;
            int day = dayAfter > MAX_SUGGESTED_DAY ? 1 : dayAfter;
            return new DueDaySuggestion(day, Basis.STATED_PAYDAY, 0,
                    "The day after the payday you told us about.");
        }
        return new DueDaySuggestion(1, Basis.DEFAULT, 0, "The 1st of each month. You can change it.");
    }

    /** Mean, over the profile's complete months, of income in the 7 days before day {@code d}. */
    static long windowScore(IncomeProfile p, int d) {
        long total = 0;
        int months = 0;
        for (YearMonth m = p.firstMonth(); !m.isAfter(p.lastMonth()); m = m.plusMonths(1)) {
            LocalDate due = DueDates.dateOf(m, d);
            LocalDate windowStart = due.minusDays(WINDOW_DAYS);
            if (YearMonth.from(windowStart).isBefore(p.firstMonth())) {
                continue; // window reaches before the data; this month cannot be scored fairly
            }
            for (LocalDate day = windowStart; day.isBefore(due); day = day.plusDays(1)) {
                total += p.incomeOn(day);
            }
            months++;
        }
        return months == 0 ? 0 : total / months;
    }

    static String ordinal(int day) {
        if (day >= 11 && day <= 13) {
            return day + "th";
        }
        return switch (day % 10) {
            case 1 -> day + "st";
            case 2 -> day + "nd";
            case 3 -> day + "rd";
            default -> day + "th";
        };
    }
}
