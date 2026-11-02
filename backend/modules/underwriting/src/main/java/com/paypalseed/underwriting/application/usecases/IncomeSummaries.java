package com.paypalseed.underwriting.application.usecases;

import com.paypalseed.underwriting.api.dto.UnderwritingDtos;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import com.paypalseed.underwriting.domain.model.IncomeBuckets;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Consent;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.RefreshState;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Week;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Builds the income summary from the stored weeks. Shared by the facade and the PayPal refresh so both read one way. */
final class IncomeSummaries {

    /**
     * Weeks read from PayPal are cut for the day of the read. For a few days after it the summary keeps that
     * alignment, otherwise every stored week would slide one bucket and the newest bucket would read as zero
     * income. Past this lag the summary is cut for the day asked, so old data shows as the gap it is.
     */
    static final long MAX_ANCHOR_LAG_DAYS = IncomeBuckets.BUCKET_DAYS;

    private IncomeSummaries() {
    }

    /** Empty without an active consent: no consent means no income signal, never a risk. */
    static Optional<IncomeSummary> read(IncomeRepository income, UUID memberId, LocalDate asOf) {
        Optional<Consent> consent = income.consent(memberId).filter(Consent::active);
        if (consent.isEmpty()) {
            return Optional.empty();
        }
        LocalDate cutFor = income.refreshState(memberId)
                .map(RefreshState::anchor)
                .filter(anchor -> anchor != null && anchor.isBefore(asOf)
                        && ChronoUnit.DAYS.between(anchor, asOf) <= MAX_ANCHOR_LAG_DAYS)
                .orElse(asOf);
        LocalDate first = IncomeBuckets.windowStart(cutFor);
        List<Long> buckets = new ArrayList<>(Collections.nCopies(UnderwritingDtos.SUMMARY_WEEKS, 0L));
        for (Week week : income.weeks(memberId, first, cutFor.minusDays(1))) {
            int bucket = (int) (ChronoUnit.DAYS.between(first, week.weekStart()) / IncomeBuckets.BUCKET_DAYS);
            buckets.set(bucket, buckets.get(bucket) + week.inflowCents());
        }
        int active = (int) buckets.stream().filter(b -> b > 0).count();
        return Optional.of(new IncomeSummary(buckets, consent.get().usualPaydayDow(), active));
    }

    /** The three monthly figures the fair-check engine takes, derived from the 13 weekly buckets. */
    static List<IncomeMonthDto> monthly(IncomeSummary summary, LocalDate today) {
        List<Long> weeks = summary.weeklyInflowCents();
        long m1 = weeks.subList(0, 4).stream().mapToLong(Long::longValue).sum();
        long m2 = weeks.subList(4, 8).stream().mapToLong(Long::longValue).sum();
        long m3 = weeks.subList(8, 13).stream().mapToLong(Long::longValue).sum() * 4L / 5L;
        YearMonth ym = YearMonth.from(today);
        List<IncomeMonthDto> out = new ArrayList<>(3);
        out.add(new IncomeMonthDto(ym.minusMonths(3).toString(), m1));
        out.add(new IncomeMonthDto(ym.minusMonths(2).toString(), m2));
        out.add(new IncomeMonthDto(ym.minusMonths(1).toString(), m3));
        return out;
    }
}
