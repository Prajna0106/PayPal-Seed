package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Allocation;
import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.Allocator;
import com.paypalseed.engines.schedule.MoneyEvent;
import com.paypalseed.engines.schedule.Rescheduler;
import com.paypalseed.engines.schedule.Schedule;
import com.paypalseed.engines.schedule.ScheduleDiff;
import com.paypalseed.engines.schedule.ScheduleErrors;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePreview;
import com.paypalseed.lending.api.dto.LendingDtos.RescheduleRequest;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.ScheduleRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.kernel.Ids;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-19: hardship reschedules (F5 options A, B, C) as new schedule versions. Preview runs the same
 * pure engine call without writing, so the member, the officer and Bryntum see exactly what an
 * approval would do.
 */
@Service
public class RescheduleService {

    private final LoanRepository loans;
    private final ScheduleRepository schedules;
    private final AttemptRepository attempts;
    private final LoanRecalculator recalculator;
    private final LoanQueries queries;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public RescheduleService(LoanRepository loans, ScheduleRepository schedules, AttemptRepository attempts,
                             LoanRecalculator recalculator, LoanQueries queries, BusinessClock clock,
                             ApplicationEventPublisher events) {
        this.loans = loans;
        this.schedules = schedules;
        this.attempts = attempts;
        this.recalculator = recalculator;
        this.queries = queries;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public ReschedulePreview preview(LoanRow loan, RescheduleRequest request) {
        LocalDate today = clock.today();
        LoanRecalculator.Projection now = recalculator.project(loan, List.of());
        Schedule proposed = propose(loan, now, request, today);
        Allocation refolded = Allocator.allocate(proposed, history(loan), loan.policy());
        return new ReschedulePreview(queries.view(loan, now.schedule(), now.allocation(), today),
                queries.view(loan, proposed, refolded, today), LoanQueries.diff(ScheduleDiff.between(now.schedule(), proposed)));
    }

    @Transactional
    public ScheduleView apply(RescheduleRequest request, String approvalRef) {
        Objects.requireNonNull(approvalRef, "approvalRef");
        LoanRow loan = loans.lock(request.loanId())
                .orElseThrow(() -> new DomainException(LendingErrors.LOAN_NOT_FOUND, "No loan " + request.loanId()));
        if (!loan.isActive()) {
            throw new DomainException(LendingErrors.LOAN_NOT_ACTIVE, "Loan is " + loan.status());
        }
        LocalDate today = clock.today();
        LoanRecalculator.Projection now = recalculator.project(loan, List.of());
        Schedule proposed = propose(loan, now, request, today);
        schedules.insert(loan.id(), proposed, loan.policyVersion(), "approval:" + approvalRef, today);
        loans.setCurrentVersion(loan.id(), proposed.version());
        attempts.cancelPlanned(loan.id(), "rescheduled");
        LoanRow relocked = loans.lock(loan.id()).orElseThrow();
        LoanRecalculator.Outcome outcome = recalculator.recalculate(relocked, today, LoanRecalculator.Cause.RESCHEDULE);
        events.publishEvent(new LendingEvents.ScheduleChanged(Ids.newId(), loan.id(), loan.memberId(), today,
                now.schedule().version(), proposed.version(), proposed.reason().name(), "approval:" + approvalRef,
                proposed.instalments().stream()
                        .map(i -> new LendingEvents.InstalmentView(i.seq(), i.dueDate(), i.amountCents())).toList()));
        events.publishEvent(new LendingEvents.MandateSyncRequested(Ids.newId(), loan.id(), loan.memberId(), today));
        return queries.view(outcome.after(), outcome.schedule(), outcome.allocation(), today);
    }

    private Schedule propose(LoanRow loan, LoanRecalculator.Projection now, RescheduleRequest r, LocalDate today) {
        Schedule s = now.schedule();
        Allocation a = now.allocation();
        int seq = r.seq() != null ? r.seq() : firstUnpaid(a);
        return switch (r.primitive()) {
            case MOVE_DATE -> Rescheduler.moveDate(s, a, seq, required(r.days(), "days"), today, loan.policy());
            case DEFER_TO_END -> Rescheduler.deferToEnd(s, a, seq, today);
            case REAMORTIZE -> Rescheduler.reamortize(s, a, required(r.extraMonths(), "extraMonths"), today, loan.policy());
        };
    }

    private List<MoneyEvent> history(LoanRow loan) {
        return queries.transactionsOf(loan.id()).stream().map(LoanTransaction::toMoneyEvent).toList();
    }

    private static int firstUnpaid(Allocation a) {
        return a.instalments().stream().filter(x -> x.remainingCents() > 0).map(InstalmentAllocation::seq).findFirst()
                .orElseThrow(() -> new DomainException(ScheduleErrors.NOTHING_OUTSTANDING, "Nothing left to reschedule"));
    }

    private static int required(Integer value, String name) {
        if (value == null) {
            throw new DomainException(ErrorCode.validation("missing-" + name, name + " is required for this option"),
                    name + " is required");
        }
        return value;
    }
}
