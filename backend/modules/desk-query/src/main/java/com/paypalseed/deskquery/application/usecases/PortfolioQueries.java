package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.BucketTotal;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.InclusionImpact;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.OnTimeRepayment;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.PortfolioSummary;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.RoleWorkload;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.StatusTotal;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Workload;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.WorkloadItem;
import com.paypalseed.deskquery.domain.model.AgingBuckets;
import com.paypalseed.deskquery.domain.model.ImpactFigures;
import com.paypalseed.deskquery.domain.model.OnTimeRate;
import com.paypalseed.deskquery.domain.model.SlaBucket;
import com.paypalseed.deskquery.infrastructure.persistence.SummaryRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Portfolio totals, inclusion impact and the officer workload, all derived from the projections. */
@Service
public class PortfolioQueries {

    private final SummaryRepository summaries;
    private final DeskQuerySettings settings;
    private final BusinessClock clock;

    PortfolioQueries(SummaryRepository summaries, DeskQuerySettings settings, BusinessClock clock) {
        this.summaries = summaries;
        this.settings = settings;
        this.clock = clock;
    }

    /** One snapshot, so the figures agree with each other even while events are being applied. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PortfolioSummary summary() {
        List<StatusTotal> byStatus = summaries.byStatus();
        Map<String, BucketTotal> found = summaries.byAgingBucket().stream()
                .collect(Collectors.toMap(BucketTotal::bucket, Function.identity(), (a, b) -> a));
        // Every bucket is listed, in severity order, so a chart never has to guess that a missing one is zero.
        List<BucketTotal> byBucket = AgingBuckets.ORDER.stream()
                .map(b -> found.getOrDefault(b, new BucketTotal(b, 0, 0))).toList();
        OnTimeRate onTime = summaries.onTime(clock.today());
        ImpactFigures impact = ImpactFigures.of(summaries.impact());
        return new PortfolioSummary(summaries.asOf().orElse(clock.now()),
                byStatus.stream().mapToLong(StatusTotal::loans).sum(),
                byStatus.stream().mapToLong(StatusTotal::outstandingCents).sum(),
                byStatus, byBucket,
                new OnTimeRepayment(onTime.instalmentsDue(), onTime.instalmentsMissed(), onTime.rateBasisPoints()),
                summaries.openHardshipCases(), summaries.hardshipOutcomes(),
                new InclusionImpact(impact.membersServed(), impact.membersWithLoan(), impact.membersLoanedWithoutCreditScore(),
                        impact.loansIssued(), impact.averageLoanSizeCents(), impact.firstLoansRepaid(),
                        impact.firstLoansRepaidOnPlan(), impact.firstLoanOnPlanRateBasisPoints()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Workload workload() {
        Instant now = clock.now();
        List<WorkloadItem> items = summaries.workload(now, settings.dueSoonWindow());
        List<RoleWorkload> byRole = items.stream().map(WorkloadItem::role).distinct().sorted()
                .map(role -> new RoleWorkload(role,
                        items.stream().filter(i -> i.role().equals(role)).mapToLong(WorkloadItem::waiting).sum(),
                        items.stream().filter(i -> i.role().equals(role) && i.slaBucket().equals(SlaBucket.OVERDUE.name()))
                                .mapToLong(WorkloadItem::waiting).sum()))
                .toList();
        return new Workload(summaries.asOf().orElse(now), items, byRole);
    }
}
