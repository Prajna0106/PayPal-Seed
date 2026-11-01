package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.Instalment;
import com.paypalseed.engines.schedule.RetryPlanner;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository.Attempt;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository.Status;
import com.paypalseed.lending.infrastructure.persistence.HardshipSupportRepository;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-11: a failed autopay charge. Records the attempt, plans at most one income-aware retry inside
 * the grace period, and raises {@code RepaymentFailed} for reminders and the hardship Guardian.
 */
@Service
public class CollectionFailureService {

    private static final Logger log = LoggerFactory.getLogger(CollectionFailureService.class);

    private final LoanRepository loans;
    private final AttemptRepository attempts;
    private final TransactionRepository transactions;
    private final HardshipSupportRepository hardship;
    private final LoanRecalculator recalculator;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public CollectionFailureService(LoanRepository loans, AttemptRepository attempts, TransactionRepository transactions,
                                    HardshipSupportRepository hardship, LoanRecalculator recalculator, BusinessClock clock,
                                    ApplicationEventPublisher events) {
        this.loans = loans;
        this.attempts = attempts;
        this.transactions = transactions;
        this.hardship = hardship;
        this.recalculator = recalculator;
        this.clock = clock;
        this.events = events;
    }

    /**
     * @param retry     true when the failed charge was our automatic retry
     * @param causedBy  PayPal event id; a second delivery of the same failure is ignored
     */
    @Transactional
    public void recordFailure(UUID loanId, boolean retry, String reason, String causedBy) {
        LoanRow loan = loans.lock(loanId).orElseThrow(() -> new IllegalStateException("unknown loan " + loanId));
        if (!loan.isActive() || loan.collectionMode() != CollectionMode.AUTOPAY) {
            log.info("Ignoring payment failure for loan {} ({} / {})", loanId, loan.status(), loan.collectionMode());
            return;
        }
        LocalDate today = clock.today();
        LoanRecalculator.Projection current = recalculator.project(loan, List.of());
        Optional<Instalment> target = current.schedule().instalments().stream()
                .filter(i -> current.allocation().of(i.seq()).remainingCents() > 0)
                .filter(i -> !i.dueDate().isAfter(today.plusDays(loan.policy().mandateDateToleranceDays())))
                .findFirst();
        if (target.isEmpty()) {
            log.info("Payment failure for loan {} but nothing is due; ignoring", loanId);
            return;
        }
        Instalment inst = target.get();
        int version = current.schedule().version();
        List<Attempt> history = attempts.forInstalment(loanId, version, inst.seq());
        if (history.stream().anyMatch(a -> causedBy.equals(a.reason()))) {
            return; // duplicate delivery of the same failure
        }
        LocalDate retryOn = null;
        if (retry) {
            history.stream().filter(a -> a.status() == Status.REQUESTED).findFirst()
                    .ifPresent(a -> attempts.setStatus(a.id(), Status.FAILED, causedBy));
        } else {
            attempts.insert(new Attempt(Ids.newId(), loanId, version, inst.seq(), history.size() + 1, "SCHEDULED", today,
                    Status.FAILED, causedBy));
            boolean retriedBefore = history.stream().anyMatch(a -> "RETRY".equals(a.kind()));
            boolean pending = transactions.pendingCents(loanId) > 0;
            boolean hardshipOpen = hardship.openCase(loanId).isPresent(); // F4 AC3: no retry while a case is open
            if (!retriedBefore && !pending && !hardshipOpen) {
                // No F3 income profile is available yet, so RetryPlanner falls back to the middle of the window.
                retryOn = RetryPlanner.retryDate(inst.dueDate(), today, Optional.empty(), loan.policy()).orElse(null);
                if (retryOn != null) {
                    attempts.insert(new Attempt(Ids.newId(), loanId, version, inst.seq(), history.size() + 2, "RETRY",
                            retryOn, Status.PLANNED, null));
                }
            }
        }
        recalculator.recalculate(loan, today, LoanRecalculator.Cause.PAYMENT);
        InstalmentAllocation alloc = current.allocation().of(inst.seq());
        events.publishEvent(new LendingEvents.RepaymentFailed(Ids.newId(), loanId, loan.memberId(), today, inst.seq(),
                inst.dueDate(), alloc.remainingCents(), history.size() + 1, reason, retryOn));
        log.info("Autopay failed for loan {} instalment {}; retry {}", loanId, inst.seq(), retryOn);
    }
}
