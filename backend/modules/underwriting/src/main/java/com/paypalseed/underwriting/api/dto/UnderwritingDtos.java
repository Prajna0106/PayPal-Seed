package com.paypalseed.underwriting.api.dto;

import java.util.List;

public final class UnderwritingDtos {

    public static final int SUMMARY_WEEKS = 13;

    private UnderwritingDtos() {
    }

    /**
     * {@code weeklyInflowCents} holds 13 consecutive 7-day buckets, oldest first; the last ends the day before the
     * summary date. {@code usualPaydayDow} is the ISO day of week income usually arrives, or null when unknown.
     */
    public record IncomeSummary(List<Long> weeklyInflowCents, Integer usualPaydayDow, int activeWeeks) {
        public IncomeSummary {
            weeklyInflowCents = List.copyOf(weeklyInflowCents);
            if (weeklyInflowCents.size() != SUMMARY_WEEKS) {
                throw new IllegalArgumentException("expected " + SUMMARY_WEEKS + " weekly buckets");
            }
        }

        /** The buckets as the primitive array the hardship engine takes. */
        public long[] weeks() {
            return weeklyInflowCents.stream().mapToLong(Long::longValue).toArray();
        }
    }
}
