package com.paypalseed.underwriting.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Consent;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Week;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The F3 income summary F5 reads. Weekly totals are bucketed into 13 consecutive 7-day windows ending the day
 * before the summary date, so a summary never sees the current, incomplete day. Until F3 reads PayPal
 * Transaction Search, the totals arrive through the internal seeding endpoint.
 */
@Service
public class IncomeSummaryService implements UnderwritingFacade {

    private static final int BUCKET_DAYS = 7;

    private final IncomeRepository income;
    private final BusinessClock clock;

    public IncomeSummaryService(IncomeRepository income, BusinessClock clock) {
        this.income = income;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IncomeSummary> incomeSummary(UUID memberId, LocalDate asOf) {
        Optional<Consent> consent = income.consent(memberId).filter(Consent::active);
        if (consent.isEmpty()) {
            return Optional.empty();
        }
        LocalDate first = asOf.minusDays((long) UnderwritingDtos.SUMMARY_WEEKS * BUCKET_DAYS);
        List<Long> buckets = new ArrayList<>(Collections.nCopies(UnderwritingDtos.SUMMARY_WEEKS, 0L));
        for (Week week : income.weeks(memberId, first, asOf.minusDays(1))) {
            int bucket = (int) (ChronoUnit.DAYS.between(first, week.weekStart()) / BUCKET_DAYS);
            buckets.set(bucket, buckets.get(bucket) + week.inflowCents());
        }
        int active = (int) buckets.stream().filter(b -> b > 0).count();
        return Optional.of(new IncomeSummary(buckets, consent.get().usualPaydayDow(), active));
    }

    /** Demo and sandbox seeding until F3 fetches Transaction Search; upserts the given weeks. */
    @Transactional
    public void record(UUID memberId, boolean consent, Integer usualPaydayDow, List<Week> weeks) {
        if (!consent) {
            income.revoke(memberId, clock.now());
            return;
        }
        income.grant(memberId, clock.now(), usualPaydayDow);
        income.upsertWeeks(memberId, weeks);
    }
}
