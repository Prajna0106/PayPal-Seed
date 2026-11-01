package com.paypalseed.deskquery.api;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.PortfolioSummary;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Workload;

/**
 * What other modules may ask of the desk read models. Nothing depends on it yet; the Desk Analyst tools are the intended
 * reader of the two summaries.
 */
public interface DeskQueryFacade {

    /**
     * Empties one projection and returns the rows removed. This is the first half of a rebuild: the second half is to replay
     * the Modulith event publication log (ADR 0015), which refills the projection through the same idempotent handlers
     * that built it. Projections that other projections read are repaired too: resetting {@code HARDSHIP} clears the
     * loans' hardship flag, resetting {@code SAVINGS} zeroes the streak on open applications, and resetting
     * {@code ASSESSMENTS} clears the assessment outcome on applications.
     */
    long reset(Projection projection);

    PortfolioSummary portfolioSummary();

    Workload workload();
}
