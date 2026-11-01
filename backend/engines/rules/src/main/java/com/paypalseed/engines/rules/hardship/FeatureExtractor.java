package com.paypalseed.engines.rules.hardship;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns the 13-week inflow series into {@link IncomeFeatures} (03 §2). Integer maths only.
 *
 * <p>The baseline is the median of all 13 weeks: it resists a few bad weeks and spikes, and when the
 * recent weeks are very low they pull it down slightly, which errs towards fewer alerts. A short history
 * is "no data", never a risk.
 */
public final class FeatureExtractor {

    private FeatureExtractor() {
    }

    /**
     * @param weeks           weekly inflow in cents, oldest first, exactly {@code lookbackWeeks} values
     * @param instalmentCents the target instalment (the oldest one still owed)
     * @return empty when there is not enough history to say anything (NO_INCOME_DATA)
     */
    public static Optional<IncomeFeatures> extract(long[] weeks, long instalmentCents, HardshipPolicy policy) {
        Objects.requireNonNull(weeks, "weeks");
        HardshipPolicy.Scoring s = policy.scoring();
        HardshipPolicy.require(weeks.length == s.lookbackWeeks(), "expected " + s.lookbackWeeks() + " weekly buckets");
        HardshipPolicy.require(instalmentCents > 0, "instalmentCents must be positive");
        for (long w : weeks) {
            HardshipPolicy.require(w >= 0, "weekly inflow must not be negative");
        }
        long active = Arrays.stream(weeks).filter(w -> w > 0).count();
        if (active < s.minHistoryWeeks()) {
            return Optional.empty();
        }

        long[] sorted = weeks.clone();
        Arrays.sort(sorted);
        long baselineWeekly = sorted[(sorted.length - 1) / 2];
        long baseline4 = s.recentWeeks() * baselineWeekly;
        long last4 = 0;
        int weeksBelow = 0;
        for (int i = weeks.length - s.recentWeeks(); i < weeks.length; i++) {
            last4 += weeks[i];
            if (weeks[i] * 10_000 < (long) s.persistenceBelowBps() * baselineWeekly) {
                weeksBelow++;
            }
        }
        int dropBps = baseline4 == 0 ? 0 : (int) Math.max(0, (baseline4 - last4) * 10_000 / baseline4);
        long recentMonthly = last4 * 30 / (7L * s.recentWeeks());
        long coverageBps = recentMonthly * s.usableShareBps() / instalmentCents;
        IncomeFeatures.Persistence persistence = weeksBelow >= s.persistenceWeeks()
                ? IncomeFeatures.Persistence.PERSISTENT
                : IncomeFeatures.Persistence.TRANSIENT;
        return Optional.of(new IncomeFeatures(baselineWeekly, baseline4, last4, dropBps, recentMonthly, coverageBps,
                weeksBelow, persistence));
    }
}
