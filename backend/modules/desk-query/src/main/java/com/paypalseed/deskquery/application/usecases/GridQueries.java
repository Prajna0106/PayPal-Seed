package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.ApplicationRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.AssessmentRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.LoanRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Page;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Total;
import com.paypalseed.deskquery.domain.model.DeskQueryErrors;
import com.paypalseed.deskquery.domain.model.GridDefinition;
import com.paypalseed.deskquery.domain.model.GridQuery;
import com.paypalseed.deskquery.domain.model.Grids;
import com.paypalseed.deskquery.infrastructure.persistence.ApplicationViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.AssessmentViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.Keyset;
import com.paypalseed.deskquery.infrastructure.persistence.LoanViewRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three keyset-paged desk lists. Each call validates the request against the list's allow-list, reads one page, and
 * on the first page adds a total that is exact for small lists and a planner estimate for large ones.
 */
@Service
public class GridQueries {

    private final ApplicationViewRepository applications;
    private final AssessmentViewRepository assessments;
    private final LoanViewRepository loans;
    private final DeskQuerySettings settings;
    private final BusinessClock clock;

    GridQueries(ApplicationViewRepository applications, AssessmentViewRepository assessments, LoanViewRepository loans,
                DeskQuerySettings settings, BusinessClock clock) {
        this.applications = applications;
        this.assessments = assessments;
        this.loans = loans;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ApplicationRow> applications(Map<String, List<String>> params) {
        GridQuery q = parse(Grids.APPLICATIONS, params);
        Instant now = clock.now();
        return page(q, applications.page(q, now, settings.dueSoonWindow()), () -> applications.total(q, now, settings.countCap()),
                applications::asOf, now);
    }

    @Transactional(readOnly = true)
    public Page<AssessmentRow> assessments(Map<String, List<String>> params) {
        GridQuery q = parse(Grids.ASSESSMENTS, params);
        Instant now = clock.now();
        return page(q, assessments.page(q, now), () -> assessments.total(q, settings.countCap()), assessments::asOf, now);
    }

    @Transactional(readOnly = true)
    public Page<LoanRow> loans(Map<String, List<String>> params) {
        GridQuery q = parse(Grids.LOANS, params);
        Instant now = clock.now();
        return page(q, loans.page(q), () -> loans.total(q, settings.countCap()), loans::asOf, now);
    }

    private GridQuery parse(GridDefinition grid, Map<String, List<String>> params) {
        return grid.parse(first(params, GridDefinition.SORT), params, limit(first(params, GridDefinition.LIMIT)),
                first(params, GridDefinition.CURSOR), settings.defaultPageSize(), settings.maxPageSize(), settings.cursors());
    }

    private <R> Page<R> page(GridQuery q, Keyset<R> keyset, Supplier<Total> total, Supplier<Optional<Instant>> asOf, Instant now) {
        String next = keyset.hasMore() ? settings.cursors().encode(q, keyset.lastKeys()) : null;
        return new Page<>(keyset.rows(), next, q.firstPage() ? total.get() : null, asOf.get().orElse(now));
    }

    private static String first(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static Integer limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new DomainException(DeskQueryErrors.INVALID_LIMIT, "limit must be a whole number");
        }
    }
}
