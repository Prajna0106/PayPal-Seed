package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.api.DeskAnalystNarrator;
import com.paypalseed.deskquery.api.DeskAnalystNarrator.DeskBriefingInput;
import com.paypalseed.deskquery.api.DeskAnalystNarrator.DeskBriefingResult;
import com.paypalseed.platform.kernel.BusinessClock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GetDeskBriefingUseCase {

    private final DeskAnalystNarrator narrator;
    private final BusinessClock clock;

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
        double reserveCoverageRatio,
        String executiveSummary,
        List<String> queuePriorities,
        List<String> riskObservations,
        String recommendedStaffingFocus,
        boolean modelGenerated
    ) {}

    public GetDeskBriefingUseCase(@Autowired(required = false) DeskAnalystNarrator narrator, BusinessClock clock) {
        this.narrator = narrator;
        this.clock = clock;
    }

    public DeskBriefingResponse getBriefing() {
        String today = clock.today().toString();
        // Deterministic baseline figures for portfolio health
        int active = 142;
        long outstandingCents = 17040000L;
        int healthy = 135;
        int atRisk = 7;
        int pendingReview = 4;
        int oldestHours = 18;
        int slaBreaches = 0;
        long reserveCents = 3500000L;
        double coverage = 2.05;

        String templateSummary = "Portfolio holds 142 active loans ($170,400 outstanding) with 135 healthy loans and 7 marked at risk.";
        List<String> templatePriorities = List.of(
            "Review 4 pending hardship cases before close of business.",
            "Prioritize oldest review case at 18 hours to prevent SLA breach."
        );
        List<String> templateObservations = List.of(
            "Healthy loan proportion stands at 95% across the portfolio.",
            "Community reserve ($35,000) provides 2.05x coverage for at-risk positions."
        );
        String templateStaffing = "Focus primary officer capacity on 4 pending hardship reviews.";

        DeskBriefingInput input = new DeskBriefingInput(
            today,
            active,
            outstandingCents,
            healthy,
            atRisk,
            pendingReview,
            oldestHours,
            slaBreaches,
            reserveCents,
            coverage,
            templateSummary,
            templatePriorities,
            templateObservations,
            templateStaffing
        );

        DeskBriefingResult result;
        if (narrator != null) {
            result = narrator.brief(input);
        } else {
            result = new DeskBriefingResult(templateSummary, templatePriorities, templateObservations, templateStaffing, false);
        }

        return new DeskBriefingResponse(
            today,
            active,
            outstandingCents,
            healthy,
            atRisk,
            pendingReview,
            oldestHours,
            slaBreaches,
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
