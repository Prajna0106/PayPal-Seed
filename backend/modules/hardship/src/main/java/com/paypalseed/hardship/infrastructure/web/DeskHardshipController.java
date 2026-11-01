package com.paypalseed.hardship.infrastructure.web;

import com.paypalseed.governance.api.dto.GovernanceDtos.Decision;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionResult;
import com.paypalseed.hardship.application.usecases.ApprovalFlow;
import com.paypalseed.hardship.application.usecases.CaseTimers;
import com.paypalseed.hardship.application.usecases.DeskQueries;
import com.paypalseed.hardship.application.usecases.HardshipScan;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Officer desk (02-design §6.2) and the idempotent internal jobs Render Workflows calls (§6.4). */
@RestController
@Validated
class DeskHardshipController {

    record DecisionRequest(@NotNull Decision decision, @NotBlank @Size(max = 64) String reasonCode, @Size(max = 500) String note,
                           @NotNull @PositiveOrZero Long evidenceViewedMs) {
    }

    record ScanRequest(LocalDate businessDate, @NotNull @Min(0) Integer shardGroup, @NotNull @Min(1) @Max(64) Integer shardCount,
                       UUID after, @Min(1) @Max(1000) Integer limit) {
    }

    record SweepResult(int fired) {
    }

    private final DeskQueries desk;
    private final ApprovalFlow approvals;
    private final HardshipScan scan;
    private final CaseTimers timers;

    DeskHardshipController(DeskQueries desk, ApprovalFlow approvals, HardshipScan scan, CaseTimers timers) {
        this.desk = desk;
        this.approvals = approvals;
        this.scan = scan;
        this.timers = timers;
    }

    @GetMapping("/api/v1/desk/hardship/cases")
    DeskQueries.Queue queue(Actor actor, @RequestParam(required = false) List<String> status,
                            @RequestParam(required = false) List<String> level, @RequestParam(required = false) List<String> origin,
                            @RequestParam(required = false) UUID before, @RequestParam(defaultValue = "50") int limit) {
        staff(actor);
        return desk.queue(orEmpty(status), orEmpty(level), orEmpty(origin), before, limit);
    }

    @GetMapping("/api/v1/desk/hardship/cases/{caseId}")
    DeskQueries.CaseDetail detail(Actor actor, @PathVariable UUID caseId) {
        staff(actor);
        return desk.detail(caseId, actor.id());
    }

    /** The maker's or the checker's decision; which one follows from the approval request. */
    @PostMapping("/api/v1/desk/hardship/cases/{caseId}/decision")
    DecisionResult decide(Actor actor, @PathVariable UUID caseId, @Valid @RequestBody DecisionRequest body,
                          @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER);
        return approvals.decide(caseId, actor.id(), actor.roles().stream().map(Enum::name).collect(Collectors.toSet()),
                body.decision(), body.reasonCode(), body.note(), body.evidenceViewedMs(), key);
    }

    @PostMapping("/api/v1/desk/hardship/cases/{caseId}/retry")
    void retry(Actor actor, @PathVariable UUID caseId) {
        actor.requireAnyRole(Role.OFFICER, Role.ADMIN);
        approvals.retry(caseId);
    }

    @PostMapping("/internal/jobs/hardship/scan")
    HardshipScan.PageResult scan(Actor actor, @Valid @RequestBody ScanRequest body) {
        actor.requireAnyRole(Role.SERVICE);
        return scan.scanPage(body.businessDate(), body.shardGroup(), body.shardCount(), body.after(),
                body.limit() == null ? 200 : body.limit());
    }

    @PostMapping("/internal/jobs/hardship/sweep")
    SweepResult sweep(Actor actor, @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        actor.requireAnyRole(Role.SERVICE);
        return new SweepResult(timers.sweep(limit));
    }

    private static void staff(Actor actor) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
