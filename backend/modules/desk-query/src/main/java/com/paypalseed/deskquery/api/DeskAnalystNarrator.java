package com.paypalseed.deskquery.api;

import java.util.List;

/**
 * Public interface for Desk Analyst Agent narration (Desk Operations & Morning Risk Briefing).
 *
 * <p>Translates deterministic portfolio metrics and queue indicators into an officer briefing
 * highlighting daily priorities, risk trends, and staffing focus.
 */
public interface DeskAnalystNarrator {

    record DeskBriefingInput(
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
        String templateExecutiveSummary,
        List<String> templateQueuePriorities,
        List<String> templateRiskObservations,
        String templateStaffingFocus
    ) {}

    record DeskBriefingResult(
        String executiveSummary,
        List<String> queuePriorities,
        List<String> riskObservations,
        String recommendedStaffingFocus,
        boolean modelGenerated
    ) {}

    DeskBriefingResult brief(DeskBriefingInput input);
}
