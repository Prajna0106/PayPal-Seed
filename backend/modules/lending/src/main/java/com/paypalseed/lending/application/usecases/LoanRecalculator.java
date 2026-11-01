package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Aging;
import com.paypalseed.engines.schedule.Allocation;
import com.paypalseed.engines.schedule.Allocator;
import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.engines.schedule.Instalment;
import com.paypalseed.engines.schedule.MoneyEvent;
import com.paypalseed.engines.schedule.RepaymentPolicy;
import com.paypalseed.engines.schedule.Schedule;
import com.paypalseed.engines.schedule.TimerPlanner;
import com.paypalseed.engines.schedule.TimerPlanner.LoanSnapshot;
import com.paypalseed.engines.schedule.TimerPlanner.PlannedRetry;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository;
import com.paypalseed.lending.infrastructure.persistence.HardshipSupportRepository;
import com.paypalseed.lending.infrastructure.persistence.HardshipSupportRepository.Hold;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.ProjectionRepository;
import com.paypalseed.lending.infrastructure.persistence.ScheduleRepository;
import com.paypalseed.lending.infrastructure.persistence.TimerRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.Ids;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single path by which a loan's derived state is recomputed (Fineract calls this
 * reprocessing). Every write to a loan ends here: it re-folds the full money history over the
 * current schedule version, re-ages the loan, records delinquency facts and transitions, settles
 * the loan's status and rebuilds its timers, all inside the caller's transaction and row lock.
 */
@Component
public class LoanRecalculator {

    /** Why a recalculation ran; recorded on bucket transitions. */
    public enum Cause {
        DISBURSEMENT,
        COB,
        PAYMENT,
        REVERSAL,
        RESCHEDULE
    }

    public record Outcome(LoanRow before, LoanRow after, Schedule schedule, Allocation allocation, Aging aging,
                          List<LoanTransaction> history) {
    }

    /** The fold of a history over a schedule, without touching the database. */
    public record Projection(Schedule schedule, Allocation allocation) {
    }

    private final LoanRepository loans;
    private final ScheduleRepository schedules;
    private final TransactionRepository transactions;
    private final ProjectionRepository projections;
    private final TimerRepository timers;
    private final AttemptRepository attempts;
    private final HardshipSupportRepository holds;
    private final ApplicationEventPublisher events;

    public LoanRecalculator(LoanRepository loans, ScheduleRepository schedules, TransactionRepository transactions,
                            ProjectionRepository projections, TimerRepository timers, AttemptRepository attempts,
                            HardshipSupportRepository holds, ApplicationEventPublisher events) {
        this.loans = loans;
        this.schedules = schedules;
        this.transactions = transactions;
        this.projections = projections;
        this.timers = timers;
        this.attempts = attempts;
        this.holds = holds;
        this.events = events;
    }

    /** Pure what-if: the allocation if {@code extra} were added to the loan's history. */
    public Projection project(LoanRow loan, List<MoneyEvent> extra) {
        Schedule schedule = schedules.loadCurrent(loan.id(), loan.currentVersion(), loan.dueDay());
        List<MoneyEvent> moneyEvents = new ArrayList<>(transactions.forLoan(loan.id()).stream()
                .map(LoanTransaction::toMoneyEvent).toList());
        moneyEvents.addAll(extra);
        return new Projection(schedule, Allocator.allocate(schedule, moneyEvents, loan.policy()));
    }

    /** Caller holds the loan's row lock inside a transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome recalculate(LoanRow loan, LocalDate asOf, Cause cause) {
        RepaymentPolicy policy = loan.policy();
        Schedule schedule = schedules.loadCurrent(loan.id(), loan.currentVersion(), loan.dueDay());
        List<LoanTransaction> history = transactions.forLoan(loan.id());
        Allocation allocation = Allocator.allocate(schedule, history.stream().map(LoanTransaction::toMoneyEvent).toList(), policy);
        Aging aging = Aging.of(schedule, allocation, asOf, policy);
        projections.replaceAllocation(loan.id(), schedule.version(), allocation,
                history.stream().map(LoanTransaction::id).toList());

        LoanStatus status = loan.status();
        LocalDate closedOn = loan.closedOn();
        boolean repaidNow = false;
        if (status == LoanStatus.ACTIVE && allocation.outstandingCents() == 0) {
            status = LoanStatus.REPAID;
            closedOn = asOf;
            repaidNow = true;
        } else if (status == LoanStatus.REPAID && allocation.outstandingCents() > 0) {
            status = LoanStatus.ACTIVE;
            closedOn = null;
            events.publishEvent(new LendingEvents.LoanReopened(Ids.newId(), loan.id(), loan.memberId(), asOf,
                    allocation.outstandingCents()));
        }

        if (status == LoanStatus.ACTIVE && !aging.graceExpiredSeqs().isEmpty()) {
            List<Hold> loanHolds = holds.allFor(loan.id());
            for (int seq : aging.graceExpiredSeqs()) {
                if (loanHolds.stream().anyMatch(h -> h.seq() == seq && h.coversOn(schedule.version(), asOf))) {
                    continue; // F5 grace hold: a human is still deciding; days past due and the bucket still move
                }
                Instalment inst = schedule.instalment(seq);
                if (projections.recordMissed(loan.id(), schedule.version(), seq, inst.dueDate(), asOf,
                        missAttribution(loanHolds, schedule.version(), seq))) {
                    events.publishEvent(new LendingEvents.InstalmentMissed(Ids.newId(), loan.id(), loan.memberId(), asOf,
                            schedule.version(), seq, inst.dueDate()));
                }
            }
        }

        Bucket bucket = status == LoanStatus.ACTIVE ? aging.bucket() : Bucket.CURRENT;
        int dpd = status == LoanStatus.ACTIVE ? aging.dpd() : 0;
        LocalDate bucketSince = loan.bucketSince();
        if (bucket != loan.bucket()) {
            bucketSince = asOf;
            projections.recordTransition(loan.id(), asOf, loan.bucket(), bucket, dpd, cause.name());
            events.publishEvent(new LendingEvents.LateBucketChanged(Ids.newId(), loan.id(), loan.memberId(), asOf,
                    loan.bucket().name(), bucket.name(), dpd, cause.name()));
            if (bucket == Bucket.CURRENT) {
                events.publishEvent(new LendingEvents.LoanCured(Ids.newId(), loan.id(), loan.memberId(), asOf,
                        loan.bucket().name()));
            }
        }

        loans.updateDerived(loan.id(), status, allocation.outstandingCents(), allocation.overpaymentCents(), dpd, bucket,
                bucketSince, closedOn);

        if (repaidNow) {
            int missed = projections.missedCount(loan.id());
            events.publishEvent(new LendingEvents.LoanRepaid(Ids.newId(), loan.id(), loan.memberId(), asOf,
                    Math.max(0, schedule.size() - missed), missed, allocation.overpaymentCents()));
            attempts.cancelPlanned(loan.id(), "loan repaid");
        }

        List<PlannedRetry> retries = attempts.planned(loan.id()).stream()
                .map(a -> new PlannedRetry(retryRef(a.id()), a.plannedOn()))
                .toList();
        timers.replacePlanned(loan.id(), TimerPlanner.plan(new LoanSnapshot(status == LoanStatus.ACTIVE,
                loan.collectionMode() == CollectionMode.AUTOPAY, schedule, allocation, retries), policy));

        LoanRow after = loans.find(loan.id()).orElseThrow();
        return new Outcome(loan, after, schedule, allocation, aging, history);
    }

    /** True while an unreleased F5 hold covers this instalment on {@code asOf}. */
    boolean isHeld(LoanRow loan, int version, int seq, LocalDate asOf) {
        return holds.unreleased(loan.id(), seq).filter(h -> h.coversOn(version, asOf)).isPresent();
    }

    /** NULL when no hold was involved; SLA_BREACH when our own decision ran late, so F6 does not count it. */
    private static String missAttribution(List<Hold> loanHolds, int version, int seq) {
        List<Hold> onInstalment = loanHolds.stream().filter(h -> h.version() == version && h.seq() == seq).toList();
        if (onInstalment.isEmpty()) {
            return null;
        }
        return onInstalment.stream().anyMatch(h -> HardshipSupportRepository.CAUSE_SLA_ESCALATION.equals(h.cause()))
                ? "SLA_BREACH" : "MEMBER";
    }

    static String retryRef(java.util.UUID attemptId) {
        return "attempt:" + attemptId;
    }
}
