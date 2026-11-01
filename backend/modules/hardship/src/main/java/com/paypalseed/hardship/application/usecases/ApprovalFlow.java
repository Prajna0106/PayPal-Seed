package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.ApprovalTier;
import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import com.paypalseed.engines.rules.hardship.OptionKind;
import com.paypalseed.engines.rules.hardship.SlaClock;
import com.paypalseed.governance.api.ApprovalStaleException;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decide;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decision;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.OpenApproval;
import com.paypalseed.governance.api.dto.GovernanceDtos.Tier;
import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalDecided;
import com.paypalseed.hardship.api.events.HardshipEvents;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.domain.model.HardshipErrors;
import com.paypalseed.hardship.domain.model.OfferModel.OptionRow;
import com.paypalseed.hardship.domain.model.OfferStaleException;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository.SagaRow;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.hardship.infrastructure.persistence.OfferRepository;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * From the member's choice to the new schedule (02-design §4.4 to §4.6): opens the approval request, records the
 * officers' decisions through governance, places the grace hold, and runs the execution saga. Every step
 * re-checks the snapshot hash, so what is applied is exactly what the member chose and the officers approved.
 */
@Service
public class ApprovalFlow {

    private static final Logger log = LoggerFactory.getLogger(ApprovalFlow.class);
    private static final int MAX_SAGA_ATTEMPTS = 5;

    public record ChoiceOutcome(CaseStatus status, String tier, Instant decisionDueAt, LocalDate holdUntil) {
    }

    private final CaseRepository cases;
    private final OfferRepository offers;
    private final CaseRecordsRepository records;
    private final OfferBuilder offerBuilder;
    private final CaseLifecycle lifecycle;
    private final OutreachService outreach;
    private final GovernanceFacade governance;
    private final LendingFacade lending;
    private final HardshipSettings settings;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate newTransaction;

    public ApprovalFlow(CaseRepository cases, OfferRepository offers, CaseRecordsRepository records, OfferBuilder offerBuilder,
                        CaseLifecycle lifecycle, OutreachService outreach, GovernanceFacade governance, LendingFacade lending,
                        HardshipSettings settings, BusinessClock clock, ApplicationEventPublisher events,
                        PlatformTransactionManager txManager) {
        this.cases = cases;
        this.offers = offers;
        this.records = records;
        this.offerBuilder = offerBuilder;
        this.lifecycle = lifecycle;
        this.outreach = outreach;
        this.governance = governance;
        this.lending = lending;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** Called right after the member's choice is stored, in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ChoiceOutcome afterChoice(CaseRow c, OptionRow option) {
        OptionKind kind = OptionKind.fromLabel(option.label());
        ApprovalTier tier = ApprovalTier.forKind(kind);
        HardshipPolicy policy = settings.policy();
        Instant now = clock.now();
        if (tier == ApprovalTier.NONE) {
            return new ChoiceOutcome(CaseStatus.OPTION_CHOSEN, tier.name(), null, null);
        }
        if (tier == ApprovalTier.AUTO_POLICY) {
            UUID request = governance.openApprovalRequest(new OpenApproval(c.id(), c.memberId(), Tier.AUTO_POLICY,
                    option.offerId(), option.snapshotHash(), null, false));
            cases.setApprovalRequest(c.id(), request);
            cases.setStatus(c.id(), CaseStatus.APPROVED);
            return new ChoiceOutcome(CaseStatus.APPROVED, tier.name(), null, null);
        }
        boolean priority = c.targetDueDate().toEpochDay() - clock.today().toEpochDay() <= policy.timing().priorityDueWithinDays();
        Instant due = SlaClock.due(now, priority, policy);
        UUID request = governance.openApprovalRequest(new OpenApproval(c.id(), c.memberId(), Tier.MAKER_CHECKER,
                option.offerId(), option.snapshotHash(), due, settings.checkerRequired()));
        cases.setApprovalRequest(c.id(), request);
        cases.setStatus(c.id(), CaseStatus.PENDING_APPROVAL);
        records.schedule(c.id(), "SLA_50", SlaClock.at(now, priority, 50, policy));
        records.schedule(c.id(), "SLA_80", SlaClock.at(now, priority, 80, policy));
        records.schedule(c.id(), "SLA_BREACH", due);
        LocalDate hold = placeHoldIfDue(cases.find(c.id()).orElseThrow());
        return new ChoiceOutcome(CaseStatus.PENDING_APPROVAL, tier.name(), due, hold);
    }

    /**
     * A grace hold (03 §9): while officers decide, the miss for the target instalment is not written if grace
     * ends within the hold window. Otherwise a HOLD_CHECK timer re-runs this rule later. Returns the hold end.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDate placeHoldIfDue(CaseRow c) {
        if (c.status() != CaseStatus.PENDING_APPROVAL) {
            return null;
        }
        HardshipPolicy.Timing t = settings.policy().timing();
        LocalDate today = clock.today();
        LocalDate missDate = c.targetDueDate().plusDays(settings.policy().scoring().graceDays() + 1L);
        if (!today.isBefore(missDate)) {
            return null;
        }
        if (missDate.toEpochDay() - today.toEpochDay() > t.holdWindowDays()) {
            records.schedule(c.id(), "HOLD_CHECK", missDate.minusDays(t.holdWindowDays()).atTime(LocalTime.of(6, 0))
                    .atZone(t.zone()).toInstant());
            return null;
        }
        LocalDate until = missDate.plusDays(t.holdDays() - 1L);
        try {
            lending.placeHold(c.loanId(), c.targetSeq(), until, c.caseRef(), "CHOSEN_OPTION_PENDING");
        } catch (DomainException e) {
            log.info("No hold for case {}: {}", c.id(), e.getMessage());
            return null;
        }
        records.schedule(c.id(), "HOLD_EXPIRY", until.plusDays(1).atStartOfDay(t.zone()).toInstant());
        return until;
    }

    /** SLA breached while officers still decide: one audited 2-day extension of the hold, never silent (03 §9). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void slaBreached(CaseRow c) {
        lifecycle.audit("HARDSHIP_SLA_BREACH", c, "SYSTEM", "hardship", Map.of("approvalRequest", String.valueOf(c.approvalRequestId())));
        log.warn("Hardship case {} breached its decision SLA", c.id());
        if (c.status() != CaseStatus.PENDING_APPROVAL) {
            return;
        }
        HardshipPolicy.Timing t = settings.policy().timing();
        LocalDate missDate = c.targetDueDate().plusDays(settings.policy().scoring().graceDays() + 1L);
        LocalDate until = missDate.plusDays(t.holdDays() - 1L + t.holdExtensionDays());
        try {
            lending.placeHold(c.loanId(), c.targetSeq(), until, c.caseRef(), "SLA_ESCALATION");
            records.rearm(c.id(), "HOLD_EXPIRY", until.plusDays(1).atStartOfDay(t.zone()).toInstant());
        } catch (DomainException e) {
            log.info("No hold extension for case {}: {}", c.id(), e.getMessage());
        }
    }

    /**
     * An officer's decision. The maker or checker role follows from the request; separation of duties is enforced
     * by governance and by the database. A stale snapshot voids the request and sends the case back to the member.
     */
    @Transactional(noRollbackFor = OfferStaleException.class)
    public DecisionResult decide(UUID caseId, UUID staffId, Set<String> roles, Decision decision, String reasonCode,
                                 String note, long evidenceViewedMs, String idempotencyKey) {
        CaseRow c = cases.lock(caseId).orElseThrow(() -> new DomainException(HardshipErrors.CASE_NOT_FOUND, "No case " + caseId));
        if (c.status() != CaseStatus.PENDING_APPROVAL || c.approvalRequestId() == null) {
            throw new DomainException(HardshipErrors.NOT_AWAITING_DECISION, "Case " + caseId + " is " + c.status());
        }
        OptionRow option = chosenOption(c);
        String current = currentHash(c, option);
        try {
            return governance.decide(new Decide(c.approvalRequestId(), staffId, roles, null, decision, reasonCode, note,
                    evidenceViewedMs, current, idempotencyKey));
        } catch (ApprovalStaleException e) {
            stale(c);
            throw new OfferStaleException("The plan changed after the member chose it; the member must confirm again",
                    Map.of("caseId", caseId.toString()));
        }
    }

    /** Governance closed a request (any tier). Approvals start the saga; rejections close the case. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onDecided(ApprovalDecided e) {
        Optional<CaseRow> found = cases.byApprovalRequest(e.requestId());
        if (found.isEmpty()) {
            return false;
        }
        CaseRow c = cases.lock(found.get().id()).orElseThrow();
        LocalDate today = clock.today();
        if ("APPROVED".equals(e.requestStatus())) {
            if (c.status() == CaseStatus.PENDING_APPROVAL) {
                cases.setStatus(c.id(), CaseStatus.APPROVED);
            } else if (c.status() != CaseStatus.APPROVED) {
                return false;
            }
            records.cancelTimers(c.id(), List.of("SLA_50", "SLA_80", "SLA_BREACH", "HOLD_CHECK"));
            OptionRow option = chosenOption(c);
            records.insertSaga(c.id(), approvalRef(c.id(), e.requestId()), e.requestId(), option.id());
            lifecycle.audit("HARDSHIP_APPROVED", c, "STAFF", e.staffRef() == null ? e.role() : e.staffRef(),
                    Map.of("role", e.role(), "reasonCode", String.valueOf(e.reasonCode())));
            events.publishEvent(new HardshipEvents.HardshipDecisionMade(Ids.newId(), c.id(), c.loanId(), c.memberId(), today,
                    "APPROVE", option.tier(), e.reasonCode()));
            return true;
        }
        if ("REJECTED".equals(e.requestStatus()) && c.status() == CaseStatus.PENDING_APPROVAL) {
            OptionRow option = chosenOption(c);
            events.publishEvent(new HardshipEvents.HardshipDecisionMade(Ids.newId(), c.id(), c.loanId(), c.memberId(), today,
                    "REJECT", option.tier(), e.reasonCode()));
            lifecycle.close(c, CaseStatus.REJECTED_BY_OFFICER, e.reasonCode() == null ? "REJECTED" : e.reasonCode());
        }
        return false;
    }

    /**
     * One saga attempt, in its own transaction: apply through lending with the approved rows (idempotent on the
     * approval reference), close the case as APPLIED. Failures are retried with backoff; a changed preview sends
     * the case back to the member instead of applying something nobody approved.
     */
    public void execute(UUID caseId) {
        try {
            newTransaction.executeWithoutResult(status -> applyOnce(caseId));
        } catch (DomainException e) {
            if ("reschedule-preview-changed".equals(e.errorCode().code())) {
                newTransaction.executeWithoutResult(status -> {
                    CaseRow c = cases.lock(caseId).orElseThrow();
                    if (c.status() == CaseStatus.APPROVED) {
                        records.updateSaga(caseId, "STALE", e.errorCode().code(), null);
                        stale(c);
                    }
                });
                return;
            }
            failed(caseId, e);
        } catch (RuntimeException e) {
            failed(caseId, e);
        }
    }

    /** The desk's retry for a case whose execution ran out of attempts. */
    @Transactional
    public void retry(UUID caseId) {
        CaseRow c = cases.lock(caseId).orElseThrow(() -> new DomainException(HardshipErrors.CASE_NOT_FOUND, "No case " + caseId));
        if (c.status() != CaseStatus.APPROVED_EXECUTION_FAILED) {
            throw new DomainException(HardshipErrors.CASE_CLOSED, "Only a failed execution can be retried");
        }
        cases.setStatus(caseId, CaseStatus.APPROVED);
        records.rearm(caseId, "SAGA_RETRY", clock.now());
    }

    private void applyOnce(UUID caseId) {
        CaseRow c = cases.lock(caseId).orElseThrow();
        Optional<SagaRow> saga = records.saga(caseId);
        if (c.status() != CaseStatus.APPROVED || saga.isEmpty() || "DONE".equals(saga.get().status())) {
            return;
        }
        OptionRow option = offers.option(saga.get().optionId()).orElseThrow();
        OptionKind kind = OptionKind.fromLabel(option.label());
        ScheduleView view = lending.applyReschedule(OfferBuilder.request(c.loanId(), kind, option.args()), saga.get().approvalRef(),
                option.proposed().stream().map(r -> new com.paypalseed.lending.api.dto.LendingDtos.Slot(r.seq(), r.dueDate(),
                        r.amountCents())).toList());
        records.updateSaga(caseId, "DONE", null, view.version());
        cases.setApplied(caseId, view.version());
        lifecycle.audit("HARDSHIP_APPLIED", c, "SYSTEM", "hardship-saga",
                Map.of("scheduleVersion", view.version(), "option", option.label(), "approvalRef", saga.get().approvalRef()));
        events.publishEvent(new HardshipEvents.HardshipApplied(Ids.newId(), c.id(), c.loanId(), c.memberId(), clock.today(),
                view.version(), option.primitive(), saga.get().approvalRef(), List.of(c.targetSeq())));
        lifecycle.close(cases.find(caseId).orElseThrow(), CaseStatus.APPLIED, "APPLIED");
    }

    private void failed(UUID caseId, RuntimeException e) {
        log.warn("Hardship saga for case {} failed: {}", caseId, e.getMessage());
        newTransaction.executeWithoutResult(status -> {
            CaseRow c = cases.lock(caseId).orElseThrow();
            Optional<SagaRow> saga = records.saga(caseId);
            if (c.status() != CaseStatus.APPROVED || saga.isEmpty()) {
                return;
            }
            String error = e.getClass().getSimpleName();
            records.updateSaga(caseId, "FAILED", error, null);
            int attempts = saga.get().attempts() + 1;
            if (attempts >= MAX_SAGA_ATTEMPTS) {
                cases.setStatus(caseId, CaseStatus.APPROVED_EXECUTION_FAILED);
                lifecycle.audit("HARDSHIP_EXECUTION_FAILED", c, "SYSTEM", "hardship-saga", Map.of("attempts", attempts, "error", error));
            } else {
                records.rearm(caseId, "SAGA_RETRY", clock.now().plus(Duration.ofMinutes(1L << attempts)));
            }
        });
    }

    /** The approval no longer matches what lending would do: a new revision goes back to the member. */
    private void stale(CaseRow c) {
        CaseRow locked = cases.find(c.id()).orElseThrow();
        lifecycle.revise(locked);
        lifecycle.audit("HARDSHIP_OFFER_STALE", locked, "SYSTEM", "hardship", Map.of("status", locked.status().name()));
        outreach.resend(c.id());
    }

    private OptionRow chosenOption(CaseRow c) {
        return offers.latestChoice(c.id())
                .filter(ch -> "CHOOSE".equals(ch.kind()))
                .flatMap(ch -> offers.option(ch.optionId()))
                .orElseThrow(() -> new DomainException(HardshipErrors.NOT_AWAITING_DECISION, "Case " + c.id() + " has no choice"));
    }

    private String currentHash(CaseRow c, OptionRow option) {
        try {
            return offerBuilder.fresh(c.loanId(), option).snapshotHash();
        } catch (DomainException e) {
            return "unavailable:" + e.errorCode().code();
        }
    }

    static String approvalRef(UUID caseId, UUID requestId) {
        return "hardship:" + caseId + ":" + requestId;
    }
}
