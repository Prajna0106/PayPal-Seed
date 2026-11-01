package com.paypalseed.underwriting.domain.model;

import com.paypalseed.underwriting.api.dto.UnderwritingDtos;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cuts income credits into the 13 consecutive 7-day buckets of the income summary. Pure maths, no I/O.
 *
 * <p>Bucket {@code i} covers {@code [first + 7i, first + 7i + 6]}. A credit outside the 91 days is ignored, which is
 * how the day the summary is cut for (not yet complete) and anything older stay out. A first or last bucket that
 * the member's activity only partly fills is simply smaller; nothing is scaled up.
 */
public final class IncomeBuckets {

    public static final int BUCKET_DAYS = 7;
    public static final int WINDOW_DAYS = UnderwritingDtos.SUMMARY_WEEKS * BUCKET_DAYS;

    /** One bucket: its first day and what came in. */
    public record Bucket(LocalDate weekStart, long inflowCents) {
    }

    private IncomeBuckets() {
    }

    /** The first day of the window that ends the day before {@code asOf}. */
    public static LocalDate windowStart(LocalDate asOf) {
        return asOf.minusDays(WINDOW_DAYS);
    }

    /**
     * Always returns 13 buckets, oldest first, empty ones included, so a refresh overwrites a week that PayPal
     * later refunded. A transaction id seen twice (a line on the edge of a PayPal search window) counts once, and
     * a credit that nets to nothing or less adds nothing.
     */
    public static List<Bucket> weekly(LocalDate asOf, Collection<IncomeEntry> credits) {
        LocalDate first = windowStart(asOf);
        long[] sums = new long[UnderwritingDtos.SUMMARY_WEEKS];
        Set<String> seen = new HashSet<>();
        for (IncomeEntry credit : credits) {
            long offset = credit.date().toEpochDay() - first.toEpochDay();
            if (offset < 0 || offset >= WINDOW_DAYS || credit.netCents() <= 0 || !seen.add(credit.transactionId())) {
                continue;
            }
            int bucket = (int) (offset / BUCKET_DAYS);
            sums[bucket] = Math.addExact(sums[bucket], credit.netCents());
        }
        List<Bucket> out = new ArrayList<>(sums.length);
        for (int i = 0; i < sums.length; i++) {
            out.add(new Bucket(first.plusDays((long) i * BUCKET_DAYS), sums[i]));
        }
        return out;
    }
}
