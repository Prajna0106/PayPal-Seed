package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.api.DeskQueryFacade;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.MemberActivityItem;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.PortfolioSummary;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Workload;
import com.paypalseed.deskquery.domain.model.MemberRefs;
import com.paypalseed.deskquery.infrastructure.persistence.ActivityRepository;
import com.paypalseed.deskquery.infrastructure.persistence.ProjectionResetRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeskQueryFacadeService implements DeskQueryFacade {

    private static final Logger log = LoggerFactory.getLogger(DeskQueryFacadeService.class);

    private final ProjectionResetRepository resets;
    private final PortfolioQueries portfolio;
    private final ActivityRepository activity;
    private final SavingMonthsRebuild monthsRebuild;

    /** The desk read models only: no member activity and no rebuild (a projector test needs neither). */
    DeskQueryFacadeService(ProjectionResetRepository resets, PortfolioQueries portfolio) {
        this(resets, portfolio, null, null);
    }

    @Autowired
    DeskQueryFacadeService(ProjectionResetRepository resets, PortfolioQueries portfolio, ActivityRepository activity,
                           SavingMonthsRebuild monthsRebuild) {
        this.resets = resets;
        this.portfolio = portfolio;
        this.activity = activity;
        this.monthsRebuild = monthsRebuild;
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

    @Override
    @Transactional(readOnly = true)
    public List<MemberActivityItem> memberActivity(UUID memberId, int limit) {
        Objects.requireNonNull(activity, "member activity is not wired");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return activity.newest(MemberRefs.ofSavings(memberId.toString()), limit);
    }

    @Override
    public int rebuildSavingMonths() {
        return Objects.requireNonNull(monthsRebuild, "the rebuild is not wired").rebuildAll();
    }
}
