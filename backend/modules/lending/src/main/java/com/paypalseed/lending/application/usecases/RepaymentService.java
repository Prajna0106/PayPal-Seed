package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Allocation;
import com.paypalseed.engines.schedule.MoneyEvent;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.LoanRecalculator.Cause;
import com.paypalseed.lending.application.usecases.LoanRecalculator.Outcome;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.lending.domain.model.LoanTransaction.Type;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records money that moved for a loan (FR-06, FR-08, FR-09): one funnel for receipts, reversals
 * and overpayment refunds. In one transaction it locks the loan, posts the journal entry, appends
 * the transaction, re-folds and re-ages the loan, and writes the outbox events (ADR 0002).
 *
 * <p>Journal lines are the difference between the fold before and after the event, so the ledger's
 * receivable for a loan always equals the loan's outstanding balance (INV-2) by construction, even
 * when a backdated or out-of-order event re-shuffles earlier allocations.
 */
@Service
public class RepaymentService {

    private static final Logger log = LoggerFactory.getLogger(RepaymentService.class);

    public enum RecordResult {
        RECORDED,
        DUPLICATE
    }

    public record Receipt(UUID loanId, String paypalTxnId, Source source, long grossCents, long feeCents,
                          Instant occurredAt, String payerRef, String causedBy) {
        public Receipt {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(paypalTxnId, "paypalTxnId");
            if (grossCents <= 0 || feeCents < 0 || feeCents > grossCents) {
                throw new IllegalArgumentException("invalid receipt amounts for " + paypalTxnId);
            }
        }
    }

    public record Reversal(UUID loanId, String paypalTxnId, String originalTxnId, long amountCents, Instant occurredAt,
                           String causedBy) {
    }

    public record OverpaymentRefund(UUID loanId, String paypalRefundId, long amountCents, Instant occurredAt) {
    }

    /** A reversal arrived before the payment it reverses; the event is retried later. */
    public static class OriginalNotRecordedYet extends RuntimeException {
        private static final long serialVersionUID = 1L;

        OriginalNotRecordedYet(String originalTxnId) {
            super("original transaction " + originalTxnId + " is not recorded yet");
        }
    }

    private final LoanRepository loans;
    private final TransactionRepository transactions;
    private final AttemptRepository attempts;
    private final LoanRecalculator recalculator;
    private final LedgerFacade ledger;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public RepaymentService(LoanRepository loans, TransactionRepository transactions, AttemptRepository attempts,
                            LoanRecalculator recalculator, LedgerFacade ledger, BusinessClock clock,
                            ApplicationEventPublisher events) {
        this.loans = loans;
        this.transactions = transactions;
        this.attempts = attempts;
        this.recalculator = recalculator;
        this.ledger = ledger;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public RecordResult recordReceipt(Receipt r) {
        if (transactions.byPaypalTxnId(r.paypalTxnId()).isPresent()) {
            return RecordResult.DUPLICATE; // cheap check before taking the lock
        }
        LoanRow loan = loans.lock(r.loanId()).orElseThrow(() -> new IllegalStateException("unknown loan " + r.loanId()));
        if (transactions.byPaypalTxnId(r.paypalTxnId()).isPresent()) {
            return RecordResult.DUPLICATE; // a concurrent delivery won the race while we waited for the lock
        }
        LocalDate today = clock.today();
        LocalDate effective = effectiveDate(loan, r.occurredAt());
        UUID id = Ids.newId();
        Allocation before = recalculator.project(loan, List.of()).allocation();
        LoanRecalculator.Projection projected = recalculator.project(loan, List.of(new MoneyEvent.Receipt(id, effective, r.grossCents())));
        Allocation after = projected.allocation();
        long applied = before.outstandingCents() - after.outstandingCents();
        long excess = after.overpaymentCents() - before.overpaymentCents();

        UUID journalEntryId = ledger.post(PostingRequest.builder("paypal:" + r.paypalTxnId(), "Loan repayment", today)
                .debit(AccountRef.program(AccountType.PROGRAM_CASH), r.grossCents() - r.feeCents())
                .debit(AccountRef.program(AccountType.PAYPAL_FEES), r.feeCents())
                .credit(AccountRef.of(AccountType.LOANS_RECEIVABLE, loan.id()), applied)
                .credit(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, loan.memberId()), excess)
                .build());
        transactions.insert(new LoanTransaction(id, loan.id(), Type.RECEIPT, r.source(), r.paypalTxnId(), null,
                r.grossCents(), r.feeCents(), r.payerRef(), r.occurredAt(), effective, journalEntryId, r.causedBy()));
        transactions.clearPending(r.paypalTxnId());

        cancelRetriesNoLongerNeeded(loan.id(), projected);
        Outcome outcome = recalculator.recalculate(loan, today, Cause.PAYMENT);
        events.publishEvent(new LendingEvents.RepaymentReceived(Ids.newId(), loan.id(), loan.memberId(), today, id,
                r.source().name(), r.grossCents(), applied, excess, outcome.after().outstandingCents(),
                outcome.after().status().name()));
        events.publishEvent(new LendingEvents.MandateSyncRequested(Ids.newId(), loan.id(), loan.memberId(), today));
        log.info("Recorded repayment {} on loan {}: applied {} excess {}", r.paypalTxnId(), loan.id(), applied, excess);
        return RecordResult.RECORDED;
    }

    @Transactional
    public RecordResult recordReversal(Reversal r) {
        if (transactions.byPaypalTxnId(r.paypalTxnId()).isPresent()) {
            return RecordResult.DUPLICATE;
        }
        LoanRow loan = loans.lock(r.loanId()).orElseThrow(() -> new IllegalStateException("unknown loan " + r.loanId()));
        if (transactions.byPaypalTxnId(r.paypalTxnId()).isPresent()) {
            return RecordResult.DUPLICATE;
        }
        LoanTransaction original = transactions.byPaypalTxnId(r.originalTxnId())
                .orElseThrow(() -> new OriginalNotRecordedYet(r.originalTxnId()));
        if (original.type() != Type.RECEIPT || !original.loanId().equals(loan.id())) {
            throw new IllegalStateException("reversal " + r.paypalTxnId() + " does not target a receipt of loan " + loan.id());
        }
        LocalDate today = clock.today();
        UUID id = Ids.newId();
        MoneyEvent.Reversal reversal = new MoneyEvent.Reversal(id, original.id(), effectiveDate(loan, r.occurredAt()), r.amountCents());
        Allocation before = recalculator.project(loan, List.of()).allocation();
        Allocation after = recalculator.project(loan, List.of(reversal)).allocation(); // rejects over-reversal
        UUID journalEntryId = ledger.post(PostingRequest.builder("paypal:" + r.paypalTxnId(), "Repayment reversed", today)
                .debit(AccountRef.of(AccountType.LOANS_RECEIVABLE, loan.id()), after.outstandingCents() - before.outstandingCents())
                .debit(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, loan.memberId()),
                        before.overpaymentCents() - after.overpaymentCents())
                .credit(AccountRef.program(AccountType.PROGRAM_CASH), r.amountCents())
                .build());
        transactions.insert(new LoanTransaction(id, loan.id(), Type.REVERSAL, Source.PAYPAL, r.paypalTxnId(), original.id(),
                r.amountCents(), 0, null, r.occurredAt(), reversal.effectiveDate(), journalEntryId, r.causedBy()));
        Outcome outcome = recalculator.recalculate(loan, today, Cause.REVERSAL);
        events.publishEvent(new LendingEvents.RepaymentReversed(Ids.newId(), loan.id(), loan.memberId(), today, id,
                original.id(), r.amountCents(), outcome.after().outstandingCents()));
        events.publishEvent(new LendingEvents.MandateSyncRequested(Ids.newId(), loan.id(), loan.memberId(), today));
        log.warn("Recorded reversal {} of {} on loan {}", r.paypalTxnId(), r.originalTxnId(), loan.id());
        return RecordResult.RECORDED;
    }

    @Transactional
    public RecordResult recordOverpaymentRefund(OverpaymentRefund r) {
        if (transactions.byPaypalTxnId(r.paypalRefundId()).isPresent()) {
            return RecordResult.DUPLICATE;
        }
        LoanRow loan = loans.lock(r.loanId()).orElseThrow(() -> new IllegalStateException("unknown loan " + r.loanId()));
        if (transactions.byPaypalTxnId(r.paypalRefundId()).isPresent()) {
            return RecordResult.DUPLICATE;
        }
        LocalDate today = clock.today();
        UUID id = Ids.newId();
        MoneyEvent.OverpaymentRefund refund = new MoneyEvent.OverpaymentRefund(id, effectiveDate(loan, r.occurredAt()), r.amountCents());
        Allocation before = recalculator.project(loan, List.of()).allocation();
        Allocation after = recalculator.project(loan, List.of(refund)).allocation();
        UUID journalEntryId = ledger.post(PostingRequest.builder("paypal:" + r.paypalRefundId(), "Overpayment refunded", today)
                .debit(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, loan.memberId()),
                        before.overpaymentCents() - after.overpaymentCents())
                .debit(AccountRef.of(AccountType.LOANS_RECEIVABLE, loan.id()), after.outstandingCents() - before.outstandingCents())
                .credit(AccountRef.program(AccountType.PROGRAM_CASH), r.amountCents())
                .build());
        transactions.insert(new LoanTransaction(id, loan.id(), Type.OVERPAYMENT_REFUND, Source.SEED, r.paypalRefundId(), null,
                r.amountCents(), 0, null, r.occurredAt(), refund.effectiveDate(), journalEntryId, "refund:" + r.paypalRefundId()));
        recalculator.recalculate(loan, today, Cause.PAYMENT);
        events.publishEvent(new LendingEvents.OverpaymentRefunded(Ids.newId(), loan.id(), loan.memberId(), today, id,
                r.amountCents()));
        return RecordResult.RECORDED;
    }

    @Transactional
    public void recordPending(UUID loanId, String paypalTxnId, long amountCents, Instant since) {
        if (transactions.byPaypalTxnId(paypalTxnId).isEmpty()) {
            transactions.recordPending(loanId, paypalTxnId, amountCents, since);
        }
    }

    /**
     * Money never counts before the loan existed: a PayPal timestamp earlier than disbursement (clock
     * skew, demo clock) is clamped to the disbursement date.
     */
    private LocalDate effectiveDate(LoanRow loan, Instant occurredAt) {
        LocalDate date = clock.businessDateFor(occurredAt);
        return date.isBefore(loan.disbursedOn()) ? loan.disbursedOn() : date;
    }

    /**
     * Settles open attempts whose instalment is now paid: a requested capture succeeded, and a planned
     * retry is cancelled. Runs before recalculating, so those retry timers are not planned again.
     */
    private void cancelRetriesNoLongerNeeded(UUID loanId, LoanRecalculator.Projection projected) {
        for (AttemptRepository.Attempt a : attempts.open(loanId)) {
            boolean stillOwed = a.version() == projected.schedule().version()
                    && projected.schedule().find(a.seq()).isPresent()
                    && projected.allocation().of(a.seq()).remainingCents() > 0;
            if (stillOwed) {
                continue;
            }
            if (a.status() == AttemptRepository.Status.REQUESTED) {
                attempts.setStatus(a.id(), AttemptRepository.Status.SUCCEEDED, null);
            } else {
                attempts.setStatus(a.id(), AttemptRepository.Status.CANCELLED, "paid another way");
            }
        }
    }
}
