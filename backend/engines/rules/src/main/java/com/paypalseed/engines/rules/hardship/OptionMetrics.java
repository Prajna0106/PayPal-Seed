package com.paypalseed.engines.rules.hardship;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How an option changes what the member owes (03 §5).
 *
 * <p>{@code meanDelayDays} is the shift of the balance-weighted average payment date, so moving $150 by 14
 * days costs the same as moving $15 by 140 days, and A, B and C compare on one scale although C changes
 * amounts. The day origin cancels out because the owed total is the same before and after.
 *
 * @param burdenBps   the largest instalment as a share of recent monthly income; null without income data
 * @param sustainable {@code burdenBps <= maxBurdenBps}; null without income data
 */
public record OptionMetrics(BigDecimal meanDelayDays, long maxInstalmentCents, Integer burdenBps, Boolean sustainable,
                            int instalmentsMoved) {

    public OptionMetrics {
        Objects.requireNonNull(meanDelayDays, "meanDelayDays");
        HardshipPolicy.require(maxInstalmentCents > 0, "maxInstalmentCents must be positive");
        HardshipPolicy.require((burdenBps == null) == (sustainable == null), "burden and sustainability go together");
        HardshipPolicy.require(instalmentsMoved >= 0, "instalmentsMoved must not be negative");
    }

    /**
     * @param recentMonthlyCents null when there is no income data
     * @throws IllegalArgumentException if the option changes the owed total (F4's primitives never do)
     */
    public static OptionMetrics compute(List<OwedRow> before, List<OwedRow> after, LocalDate asOf, Long recentMonthlyCents,
                                        HardshipPolicy policy) {
        Objects.requireNonNull(asOf, "asOf");
        HardshipPolicy.require(!before.isEmpty() && !after.isEmpty(), "an option needs owed rows before and after");
        long outstanding = sum(before);
        HardshipPolicy.require(outstanding == sum(after),
                "an option must keep the owed total (" + outstanding + " before, " + sum(after) + " after)");

        BigDecimal shift = BigDecimal.valueOf(weightedDays(after, asOf) - weightedDays(before, asOf));
        BigDecimal meanDelayDays = shift.divide(BigDecimal.valueOf(outstanding), 2, RoundingMode.HALF_UP);
        long maxInstalment = after.stream().mapToLong(OwedRow::remainingCents).max().orElseThrow();

        Integer burdenBps = null;
        Boolean sustainable = null;
        if (recentMonthlyCents != null && recentMonthlyCents > 0) {
            burdenBps = (int) Math.min(Integer.MAX_VALUE, maxInstalment * 10_000 / recentMonthlyCents);
            sustainable = burdenBps <= policy.options().maxBurdenBps();
        }
        return new OptionMetrics(meanDelayDays, maxInstalment, burdenBps, sustainable, moved(before, after));
    }

    private static long sum(List<OwedRow> rows) {
        return rows.stream().mapToLong(OwedRow::remainingCents).sum();
    }

    private static long weightedDays(List<OwedRow> rows, LocalDate asOf) {
        long total = 0;
        for (OwedRow r : rows) {
            total = Math.addExact(total, Math.multiplyExact(r.remainingCents(), ChronoUnit.DAYS.between(asOf, r.dueDate())));
        }
        return total;
    }

    /** Rows of the new plan with no identical (date and amount) row in the old plan, matched one to one. */
    private static int moved(List<OwedRow> before, List<OwedRow> after) {
        List<OwedRow> unmatched = new ArrayList<>(before);
        int moved = 0;
        for (OwedRow r : after) {
            if (!unmatched.remove(r)) {
                moved++;
            }
        }
        return moved;
    }
}
