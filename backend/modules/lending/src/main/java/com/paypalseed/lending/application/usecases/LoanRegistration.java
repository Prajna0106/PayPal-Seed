package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.engines.schedule.RepaymentPolicy;
import com.paypalseed.engines.schedule.Schedule;
import com.paypalseed.engines.schedule.ScheduleGenerator;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.ScheduleRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-01: a disbursed loan gets schedule version 1, its receivable is booked, and its timers are planned. */
@Service
public class LoanRegistration {

    private final LoanRepository loans;
    private final ScheduleRepository schedules;
    private final LoanRecalculator recalculator;
    private final LedgerFacade ledger;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public LoanRegistration(LoanRepository loans, ScheduleRepository schedules, LoanRecalculator recalculator,
                            LedgerFacade ledger, BusinessClock clock, ApplicationEventPublisher events) {
        this.loans = loans;
        this.schedules = schedules;
        this.recalculator = recalculator;
        this.ledger = ledger;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public UUID register(RegisterDisbursedLoan command) {
        var existing = loans.idByDisbursementRef(command.disbursementRef());
        if (existing.isPresent()) {
            return existing.get();
        }
        RepaymentPolicy policy = RepaymentPolicy.V1;
        Schedule schedule = ScheduleGenerator.generate(command.principalCents(), command.instalmentCount(),
                command.dueDay(), command.disbursedOn(), policy);
        UUID loanId = Ids.newId();
        LoanRow loan = new LoanRow(loanId, command.memberId(), command.principalCents(), command.instalmentCount(),
                command.dueDay(), command.disbursedOn(), policy.version(), LoanStatus.ACTIVE, CollectionMode.MANUAL, 1,
                command.principalCents(), 0, 0, Bucket.CURRENT, command.disbursedOn(), null, null, 0);
        if (!loans.insert(loan, command.disbursementRef())) {
            return loans.idByDisbursementRef(command.disbursementRef()).orElseThrow(); // concurrent registration won
        }
        schedules.insert(loanId, schedule, policy.version(), "disbursement:" + command.disbursementRef(), clock.today());
        ledger.post(PostingRequest.builder("disbursement:" + command.disbursementRef(), "Loan disbursed", command.disbursedOn())
                .debit(AccountRef.of(AccountType.LOANS_RECEIVABLE, loanId), command.principalCents())
                .credit(AccountRef.program(AccountType.PROGRAM_CASH), command.principalCents())
                .build());
        recalculator.recalculate(loans.lock(loanId).orElseThrow(), clock.today(), LoanRecalculator.Cause.DISBURSEMENT);
        events.publishEvent(new LendingEvents.ScheduleGenerated(Ids.newId(), loanId, command.memberId(), clock.today(),
                schedule.version(), schedule.instalments().stream()
                        .map(i -> new LendingEvents.InstalmentView(i.seq(), i.dueDate(), i.amountCents())).toList()));
        return loanId;
    }
}
