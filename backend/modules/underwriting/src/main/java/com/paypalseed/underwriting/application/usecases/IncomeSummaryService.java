package com.paypalseed.underwriting.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Consent;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Week;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The F3 income summary F5 reads, plus F2 <-> F3 facade delegation. Weekly totals are bucketed into 13
 * consecutive 7-day windows ending the day before the summary date, so a summary never sees the current,
 * incomplete day.
 */
@Service
public class IncomeSummaryService implements UnderwritingFacade {

    private static final int BUCKET_DAYS = 7;

    private final IncomeRepository income;
    private final BusinessClock clock;
    private final FairLoanCheckService fairLoanCheck;

    public IncomeSummaryService(IncomeRepository income, BusinessClock clock) {
        this(income, clock, null);
    }

    @Autowired
    public IncomeSummaryService(
        IncomeRepository income,
        BusinessClock clock,
        @Autowired(required = false) FairLoanCheckService fairLoanCheck
    ) {
        this.income = income;
        this.clock = clock;
        this.fairLoanCheck = fairLoanCheck;
    }

    @Override
    public ConsentStatusView grantConsent(UUID memberId) {
        if (fairLoanCheck != null) {
            return fairLoanCheck.grantConsent(memberId);
        }
        return UnderwritingFacade.super.grantConsent(memberId);
    }

    @Override
    public ConsentStatusView revokeConsent(UUID memberId) {
        if (fairLoanCheck != null) {
            return fairLoanCheck.revokeConsent(memberId);
        }
        return UnderwritingFacade.super.revokeConsent(memberId);
    }

    @Override
    public ConsentStatusView getConsentStatus(UUID memberId) {
        if (fairLoanCheck != null) {
            return fairLoanCheck.getConsentStatus(memberId);
        }
        return UnderwritingFacade.super.getConsentStatus(memberId);
    }

    @Override
    public Optional<FairCheckCardView> findAssessment(UUID assessmentId) {
        if (fairLoanCheck != null) {
            return fairLoanCheck.findAssessment(assessmentId);
        }
        return UnderwritingFacade.super.findAssessment(assessmentId);
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalLong verifiedMonthlyIncomeCents(UUID memberId, LocalDate asOf) {
        if (fairLoanCheck != null) {
            List<FairCheckCardView> existing = fairLoanCheck.listMemberAssessments(memberId);
            if (!existing.isEmpty() && existing.get(0).averageMonthlyIncomeCents() > 0) {
                return OptionalLong.of(existing.get(0).averageMonthlyIncomeCents());
            }
        }
        LocalDate effectiveDate = asOf != null ? asOf : (clock != null ? clock.today() : LocalDate.now());
        Optional<IncomeSummary> summary = incomeSummary(memberId, effectiveDate);
        if (summary.isPresent() && summary.get().activeWeeks() > 0) {
            long total13WeeksCents = summary.get().weeklyInflowCents().stream().mapToLong(Long::longValue).sum();
            if (total13WeeksCents > 0) {
                return OptionalLong.of(total13WeeksCents / 3L);
            }
        }
        return OptionalLong.empty();
    }

    @Override
    @Transactional
    public Optional<FairCheckCardView> assessForLoanApplication(
        UUID memberId,
        UUID applicationId,
        String purpose,
        long requestedAmountCents,
        int requestedTermMonths,
        long statedMonthlyIncomeCents,
        int completedLoansOnTime,
        boolean hasActiveLoan
    ) {
        if (fairLoanCheck == null) {
            return Optional.empty();
        }
        fairLoanCheck.grantConsent(memberId);

        LocalDate today = clock != null ? clock.today() : LocalDate.now();
        List<IncomeMonthDto> monthlyIncomes = new ArrayList<>();
        Optional<IncomeSummary> summary = incomeSummary(memberId, today);
        if (summary.isPresent() && summary.get().activeWeeks() > 0) {
            List<Long> weeks = summary.get().weeklyInflowCents();
            long m1 = weeks.subList(0, 4).stream().mapToLong(Long::longValue).sum();
            long m2 = weeks.subList(4, 8).stream().mapToLong(Long::longValue).sum();
            long m3 = weeks.subList(8, 13).stream().mapToLong(Long::longValue).sum() * 4L / 5L;
            YearMonth ym = YearMonth.from(today);
            monthlyIncomes.add(new IncomeMonthDto(ym.minusMonths(3).toString(), m1));
            monthlyIncomes.add(new IncomeMonthDto(ym.minusMonths(2).toString(), m2));
            monthlyIncomes.add(new IncomeMonthDto(ym.minusMonths(1).toString(), m3));
        } else if (statedMonthlyIncomeCents > 0) {
            YearMonth ym = YearMonth.from(today);
            for (int i = 6; i >= 1; i--) {
                monthlyIncomes.add(new IncomeMonthDto(ym.minusMonths(i).toString(), statedMonthlyIncomeCents));
            }
        }

        FairCheckCardView card = fairLoanCheck.runFairLoanCheck(
            memberId,
            new RunFairCheckCommand(
                applicationId,
                purpose,
                requestedAmountCents,
                requestedTermMonths,
                monthlyIncomes,
                statedMonthlyIncomeCents,
                0L,
                completedLoansOnTime,
                hasActiveLoan
            )
        );
        return Optional.of(card);
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
