package com.paypalseed.lending.infrastructure.web;

import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePrimitive;
import com.paypalseed.lending.api.dto.LendingDtos.RescheduleRequest;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.api.dto.LendingDtos.VersionView;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.CloseOfBusiness;
import com.paypalseed.lending.application.usecases.CloseOfBusiness.ShardResult;
import com.paypalseed.lending.application.usecases.LoanQueries;
import com.paypalseed.lending.application.usecases.LoanQueries.Integrity;
import com.paypalseed.lending.application.usecases.LoanQueries.TransactionView;
import com.paypalseed.lending.infrastructure.persistence.CobRepository;
import com.paypalseed.lending.infrastructure.persistence.CobRepository.CobRun;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Loan officer desk (02-design §6.2) and idempotent internal jobs for Render Workflows (§6.3). */
@RestController
class DeskAndJobsController {

    record RegisterLoanRequest(@NotNull UUID memberId, @NotNull @Positive Long principalCents,
                               @NotNull @Min(1) @Max(36) Integer instalmentCount, @NotNull @Min(1) @Max(31) Integer dueDay,
                               @NotNull LocalDate disbursedOn, @NotBlank @Size(max = 128) String disbursementRef) {
    }

    record ApplyRescheduleRequest(@NotNull ReschedulePrimitive primitive, Integer seq, @Min(1) @Max(14) Integer days,
                                  @Min(1) @Max(6) Integer extraMonths, @NotBlank @Size(max = 128) String approvalRef) {
    }

    record CatchUpResult(LocalDate businessDate, List<CobRun> runs) {
    }

    record ReconcileResult(int loans) {
    }

    private final LoanQueries queries;
    private final LendingFacade lending;
    private final CloseOfBusiness cob;
    private final CobRepository cobRuns;
    private final LoanRepository loans;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    DeskAndJobsController(LoanQueries queries, LendingFacade lending, CloseOfBusiness cob, CobRepository cobRuns,
                          LoanRepository loans, BusinessClock clock, ApplicationEventPublisher events) {
        this.queries = queries;
        this.lending = lending;
        this.cob = cob;
        this.cobRuns = cobRuns;
        this.loans = loans;
        this.clock = clock;
        this.events = events;
    }

    // ---------------------------------------------------------------- desk (staff)

    @GetMapping("/api/v1/desk/loans/{loanId}/schedule")
    ScheduleView deskSchedule(Actor actor, @PathVariable UUID loanId, @RequestParam Optional<Integer> version) {
        staff(actor);
        return queries.schedule(queries.loan(loanId), version);
    }

    @GetMapping("/api/v1/desk/loans/{loanId}/schedule/versions")
    List<VersionView> deskVersions(Actor actor, @PathVariable UUID loanId) {
        staff(actor);
        return queries.versions(queries.loan(loanId));
    }

    @GetMapping("/api/v1/desk/loans/{loanId}/transactions")
    List<TransactionView> deskTransactions(Actor actor, @PathVariable UUID loanId) {
        staff(actor);
        return queries.transactions(queries.loan(loanId));
    }

    /** INV-2: allocation, cached state and ledger agree for this loan. */
    @GetMapping("/api/v1/desk/loans/{loanId}/integrity")
    Integrity integrity(Actor actor, @PathVariable UUID loanId) {
        staff(actor);
        return queries.integrity(loanId);
    }

    @GetMapping("/api/v1/desk/cob-runs")
    List<CobRun> cobRuns(Actor actor, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        staff(actor);
        return cobRuns.between(from, to);
    }

    // ---------------------------------------------------------------- internal jobs (service token)

    @PostMapping("/internal/jobs/cob/{date}/start")
    CobRun startCob(Actor actor, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                    @RequestParam(defaultValue = "api") @Size(max = 128) String trigger) {
        service(actor);
        return cob.start(date, trigger);
    }

    @PostMapping("/internal/jobs/cob/{date}/shards/{shard}")
    ShardResult cobShard(Actor actor, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @PathVariable @Min(0) @Max(63) int shard,
                         @RequestParam(defaultValue = "2000") @Min(1) @Max(50_000) int maxLoans) {
        service(actor);
        return cob.processShard(date, shard, maxLoans);
    }

    @PostMapping("/internal/jobs/cob/{date}/finish")
    CobRun finishCob(Actor actor, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service(actor);
        return cob.finish(date);
    }

    /** Closes every missing date up to today (or {@code to}), in order. Used after the demo clock moves. */
    @PostMapping("/internal/jobs/cob/catch-up")
    CatchUpResult catchUp(Actor actor, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) Optional<LocalDate> to,
                          @RequestParam(defaultValue = "api") @Size(max = 128) String trigger) {
        service(actor);
        LocalDate target = to.orElse(clock.today());
        return new CatchUpResult(target, cob.catchUp(target, trigger));
    }

    /** Daily safety net for ADR 0005: re-derive and reconcile every autopay mandate. */
    @PostMapping("/internal/jobs/mandates/reconcile")
    @Transactional
    ReconcileResult reconcileMandates(Actor actor) {
        service(actor);
        List<UUID> ids = loans.activeAutopayLoans();
        for (UUID id : ids) {
            LoanSummary loan = lending.loan(id).orElseThrow();
            events.publishEvent(new LendingEvents.MandateSyncRequested(Ids.newId(), id, loan.memberId(), clock.today()));
        }
        return new ReconcileResult(ids.size());
    }

    /** F2 hands over a disbursed loan here (and the demo seeds loans through it). */
    @PostMapping("/internal/loans/disbursed")
    ResponseEntity<LoanSummary> register(Actor actor, @Valid @RequestBody RegisterLoanRequest body) {
        service(actor);
        LoanSummary summary = lending.registerDisbursedLoan(new RegisterDisbursedLoan(body.memberId(), body.principalCents(),
                body.instalmentCount(), body.dueDay(), body.disbursedOn(), body.disbursementRef()));
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    /** F5 governance applies an approved hardship option (maker-checker happens before this call). */
    @PostMapping("/internal/loans/{loanId}/reschedules")
    ScheduleView reschedule(Actor actor, @PathVariable UUID loanId, @Valid @RequestBody ApplyRescheduleRequest body) {
        service(actor);
        return lending.applyReschedule(new RescheduleRequest(loanId, body.primitive(), body.seq(), body.days(),
                body.extraMonths()), body.approvalRef());
    }

    private static void staff(Actor actor) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
    }

    private static void service(Actor actor) {
        actor.requireAnyRole(Role.SERVICE);
    }
}
