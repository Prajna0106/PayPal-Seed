package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Allocation;
import com.paypalseed.engines.schedule.MoneyEvent;
import com.paypalseed.lending.api.dto.LendingDtos.CoverOutcome;
import com.paypalseed.lending.api.dto.LendingDtos.CoverResult;
import com.paypalseed.lending.api.dto.LendingDtos.ReserveCover;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.LoanRecalculator.Cause;
import com.paypalseed.lending.application.usecases.LoanRecalculator.Outcome;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.lending.domain.model.LoanTransaction.Type;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts money the loss reserve paid onto a loan's schedule (ADR 0019, ADR 0020).
 *
 * <p>Reserve has already posted {@code debit LOSS_RESERVE, credit LOANS_RECEIVABLE(loan)} in the ledger, so this
 * records the same money as a receipt with source {@code RESERVE} and posts <em>nothing</em>: the journal entry id is
 * kept on the transaction so the books can be followed, and a second posting would count the money twice. The money
 * runs through the same allocation waterfall as a member's payment (ADR 0006): what is due first, then what is next.
 *
 * <p>It is idempotent on {@code approvalRef}, which is the money identity of the receipt. It never applies more than
 * the loan still owes: a member who paid in the meantime leaves less to cover, and a loan that is already paid takes
 * nothing. The ledger credited the whole cover in those cases, so the unused part is logged for the desk, and the
 * result says how much it was.
 */
@Service
public class ApplyReserveCover {

    private static final Logger log = LoggerFactory.getLogger(ApplyReserveCover.class);

    private final LoanRepository loans;
    private final TransactionRepository transactions;
    private final LoanRecalculator recalculator;
    private final RepaymentService repayments;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public ApplyReserveCover(LoanRepository loans, TransactionRepository transactions, LoanRecalculator recalculator,
                             RepaymentService repayments, BusinessClock clock, ApplicationEventPublisher events) {
        this.loans = loans;
        this.transactions = transactions;
        this.recalculator = recalculator;
        this.repayments = repayments;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public CoverResult apply(ReserveCover cover) {
        if (transactions.byPaypalTxnId(cover.approvalRef()).isPresent()) {
            return replay(cover); // cheap check before taking the lock
        }
        LoanRow loan = loans.lock(cover.loanId())
                .orElseThrow(() -> new DomainException(LendingErrors.LOAN_NOT_FOUND, "No loan " + cover.loanId()));
        if (transactions.byPaypalTxnId(cover.approvalRef()).isPresent()) {
            return replay(cover); // a concurrent delivery won the race while we waited for the lock
        }
        long owed = loan.status() == LoanStatus.CANCELLED
                ? 0
                : recalculator.project(loan, List.of()).allocation().outstandingCents();
        long applied = Math.min(cover.amountCents(), owed);
        long unused = cover.amountCents() - applied;
        if (applied == 0) {
            log.warn("Reserve cover {} of {} cents arrived for loan {} that owes nothing; the ledger credited it, nothing applied",
                    cover.approvalRef(), cover.amountCents(), loan.id());
            return new CoverResult(CoverOutcome.NOTHING_DUE, 0, unused, owed);
        }

        LocalDate today = clock.today();
        // Money never counts before the loan existed.
        LocalDate effective = cover.businessDate().isBefore(loan.disbursedOn()) ? loan.disbursedOn() : cover.businessDate();
        var id = Ids.newId();
        LoanRecalculator.Projection projected =
                recalculator.project(loan, List.of(new MoneyEvent.Receipt(id, effective, applied)));
        transactions.insert(new LoanTransaction(id, loan.id(), Type.RECEIPT, Source.RESERVE, cover.approvalRef(), null,
                applied, 0, null, cover.occurredAt(), effective, cover.journalEntryId(), cover.approvalRef()));

        repayments.cancelRetriesNoLongerNeeded(loan.id(), projected);
        Outcome outcome = recalculator.recalculate(loan, today, Cause.PAYMENT);
        events.publishEvent(new LendingEvents.RepaymentReceived(Ids.newId(), loan.id(), loan.memberId(), today, id,
                Source.RESERVE.name(), applied, applied, 0, outcome.after().outstandingCents(),
                outcome.after().status().name()));
        events.publishEvent(new LendingEvents.MandateSyncRequested(Ids.newId(), loan.id(), loan.memberId(), today));
        if (unused > 0) {
            log.warn("Reserve cover {} gave {} cents more than loan {} owed; the ledger credited all {} cents, lending applied {}",
                    cover.approvalRef(), unused, loan.id(), cover.amountCents(), applied);
        } else {
            log.info("Applied reserve cover {} of {} cents to loan {}", cover.approvalRef(), applied, loan.id());
        }
        return new CoverResult(CoverOutcome.APPLIED, applied, unused, outcome.after().outstandingCents());
    }

    private CoverResult replay(ReserveCover cover) {
        LoanTransaction earlier = transactions.byPaypalTxnId(cover.approvalRef()).orElseThrow();
        if (!earlier.loanId().equals(cover.loanId())) {
            throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED, "The approval reference " + cover.approvalRef()
                    + " was used for another loan");
        }
        long outstanding = loans.find(cover.loanId()).map(LoanRow::outstandingCents).orElse(0L);
        return new CoverResult(CoverOutcome.ALREADY_APPLIED, earlier.amountCents(), 0, outstanding);
    }
}
