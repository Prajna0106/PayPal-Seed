package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.api.DeskQueryFacade;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.PortfolioSummary;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Workload;
import com.paypalseed.deskquery.infrastructure.persistence.ProjectionResetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeskQueryFacadeService implements DeskQueryFacade {

    private static final Logger log = LoggerFactory.getLogger(DeskQueryFacadeService.class);

    private final ProjectionResetRepository resets;
    private final PortfolioQueries portfolio;

    DeskQueryFacadeService(ProjectionResetRepository resets, PortfolioQueries portfolio) {
        this.resets = resets;
        this.portfolio = portfolio;
    }

    @Override
    @Transactional
    public long reset(Projection projection) {
        long removed = resets.reset(projection);
        log.warn("Reset desk projection {} ({} rows removed); replay the event publication log to rebuild it", projection, removed);
        return removed;
    }

    @Override
    public PortfolioSummary portfolioSummary() {
        return portfolio.summary();
    }

    @Override
    public Workload workload() {
        return portfolio.workload();
    }
}
