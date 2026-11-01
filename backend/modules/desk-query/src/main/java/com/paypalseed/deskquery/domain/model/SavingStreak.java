package com.paypalseed.deskquery.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Saving history as the desk shows it: the run of consecutive calendar months with at least one deposit, ending at the
 * member's latest deposit month. It is computed from the set of months, so deposits may arrive in any order and be
 * delivered twice. Months are UTC calendar months of the deposit instant.
 */
public final class SavingStreak {

    private SavingStreak() {
    }

    public static LocalDate monthOf(Instant depositedAt) {
        return depositedAt.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }

    public static int of(Collection<LocalDate> depositMonths) {
        List<LocalDate> months = depositMonths.stream().map(m -> m.withDayOfMonth(1)).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        if (months.isEmpty()) {
            return 0;
        }
        int streak = 1;
        for (int i = 1; i < months.size(); i++) {
            if (!months.get(i).equals(months.get(i - 1).minusMonths(1))) {
                break;
            }
            streak++;
        }
        return streak;
    }
}
