package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.api.DeskAnalystNarrator;
import com.paypalseed.deskquery.api.DeskAnalystNarrator.DeskBriefingInput;
import com.paypalseed.deskquery.api.DeskAnalystNarrator.DeskBriefingResult;
import com.paypalseed.deskquery.domain.model.DeskBriefingFigures;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.reserve.api.dto.ReserveDtos.ReserveSummary;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The officer's morning briefing. Every figure is read from the desk projections (portfolio, hardship cases, workload) and
 * from the fund's own summary; the analyst agent may only reword them, and its text is checked against these same figures
 * before it is returned (ADR 0009).
 */
@Service
public class GetDeskBriefingUseCase {

    private static final double BASIS_POINTS_PER_UNIT = 10_000.0;

    private final DeskAnalystNarrator narrator;
    private final PortfolioQueries portfolio;
    private final ReserveFacade reserve;
    private final BusinessClock clock;

    /**
     * {@code reserveCoverageRatio} is the fund balance over the open exposure, null when no loan needs cover (a ratio over
     * nothing owed would be meaningless).
     */
    public record DeskBriefingResponse(
        String businessDate,
        int activeLoansCount,
        long totalOutstandingCents,
        int healthyLoansCount,
        int atRiskLoansCount,
        int casesPendingReviewCount,
        int oldestCaseHours,
        int slaBreachesCount,
        long reserveBalanceCents,
        Double reserveCoverageRatio,
        String executiveSummary,
        List<String> queuePriorities,
        List<String> riskObservations,
        String recommendedStaffingFocus,
        boolean modelGenerated
    ) {}

    public GetDeskBriefingUseCase(@Autowired(required = false) DeskAnalystNarrator narrator, PortfolioQueries portfolio,
                                  ReserveFacade reserve, BusinessClock clock) {
        this.narrator = narrator;
        this.portfolio = portfolio;
        this.reserve = reserve;
        this.clock = clock;
    }

    public DeskBriefingResponse getBriefing() {
        String today = clock.today().toString();
        DeskBriefingFigures figures = portfolio.briefingFigures();
        ReserveSummary fund = reserve.summary();
        long reserveCents = fund.balanceCents();
        Double coverage = fund.coverageRatioBps() == null ? null : fund.coverageRatioBps() / BASIS_POINTS_PER_UNIT;

        BriefingTemplates template = BriefingTemplates.of(figures, reserveCents, coverage);
        DeskBriefingInput input = new DeskBriefingInput(
            today,
            figures.activeLoans(),
            figures.outstandingCents(),
            figures.healthyLoans(),
            figures.atRiskLoans(),
            figures.openHardshipCases(),
            figures.oldestOpenCaseHours(),
            figures.slaBreaches(),
            reserveCents,
            coverage == null ? 0.0 : coverage,
            template.summary(),
            template.priorities(),
            template.observations(),
            template.staffing()
        );

        DeskBriefingResult result;
        if (narrator != null) {
            result = narrator.brief(input);
        } else {
            result = new DeskBriefingResult(template.summary(), template.priorities(), template.observations(),
                template.staffing(), false);
        }

        return new DeskBriefingResponse(
            today,
            figures.activeLoans(),
            figures.outstandingCents(),
            figures.healthyLoans(),
            figures.atRiskLoans(),
            figures.openHardshipCases(),
            figures.oldestOpenCaseHours(),
            figures.slaBreaches(),
            reserveCents,
            coverage,
            result.executiveSummary(),
            result.queuePriorities(),
            result.riskObservations(),
            result.recommendedStaffingFocus(),
            result.modelGenerated()
        );
    }
}
