package com.paypalseed.deskquery.infrastructure.web;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.ApplicationRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.AssessmentRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.LoanRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Page;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.PortfolioSummary;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Workload;
import com.paypalseed.deskquery.application.usecases.GridQueries;
import com.paypalseed.deskquery.application.usecases.PortfolioQueries;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read models for the officer desk (AG Grid server-side rows and the Bryntum workload view). Roles OFFICER, CHECKER or ADMIN;
 * a member, a service or an agent gets 403. Nothing here writes. Unknown sort columns, filters and values are refused with
 * 422 problems rather than ignored, so a typo never shows an unfiltered list as if it were filtered.
 */
@RestController
class DeskQueryController {

    private final GridQueries grids;
    private final PortfolioQueries portfolio;

    DeskQueryController(GridQueries grids, PortfolioQueries portfolio) {
        this.grids = grids;
        this.portfolio = portfolio;
    }

    @GetMapping("/api/v1/desk/applications")
    ResponseEntity<Page<ApplicationRow>> applications(Actor actor, @RequestParam MultiValueMap<String, String> params) {
        staff(actor);
        return noStore(grids.applications(params));
    }

    @GetMapping("/api/v1/desk/assessments")
    ResponseEntity<Page<AssessmentRow>> assessments(Actor actor, @RequestParam MultiValueMap<String, String> params) {
        staff(actor);
        return noStore(grids.assessments(params));
    }

    @GetMapping("/api/v1/desk/portfolio/loans")
    ResponseEntity<Page<LoanRow>> loans(Actor actor, @RequestParam MultiValueMap<String, String> params) {
        staff(actor);
        return noStore(grids.loans(params));
    }

    @GetMapping("/api/v1/desk/portfolio/summary")
    ResponseEntity<PortfolioSummary> summary(Actor actor) {
        staff(actor);
        return noStore(portfolio.summary());
    }

    @GetMapping("/api/v1/desk/workload")
    ResponseEntity<Workload> workload(Actor actor) {
        staff(actor);
        return noStore(portfolio.workload());
    }

    private static void staff(Actor actor) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
    }

    /** Read models change with every event; a cached page would show an officer yesterday's queue. */
    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
