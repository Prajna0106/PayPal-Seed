package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Allocation;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.domain.model.LoanApplication;
import com.paypalseed.lending.domain.model.LoanApplication.ApplicationStatus;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.lending.domain.model.LoanTransaction.Type;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.TimerRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unwinds a loan whose payout PayPal took back after the loan was registered (ADR 0024).
 *
 * <p>The member never kept the money, so the loan must not stay live. In the caller's transaction, with the
 * application and then the loan locked, this:
 * <ol>
 *   <li>posts one new journal entry that reverses the disbursement (debit program cash, credit the receivable),
 *       moves what the member had already paid into their held overpayment, and hands back to the fund what the loss
 *       reserve had paid; the earlier entries are never touched;</li>
 *   <li>closes the loan as {@code DISBURSEMENT_RETURNED} with nothing owed, and removes its timers and planned
 *       retries, so no schedule runs, no autopay is asked for, no reminder is sent and no instalment can be missed;</li>
 *   <li>puts the application back to {@code APPROVED} so an officer can disburse again, with a new payout reference,
 *       once the recipient is fixed, or reject it;</li>
 *   <li>appends a governance audit entry and publishes {@link LendingEvents.LoanVoided}.</li>
 * </ol>
 *
 * <p>Idempotent: the application leaves {@code DISBURSED} in the same transaction, so a replayed event finds nothing
 * to do, and the ledger entry is keyed by the loan. What the member paid before the return is recorded in the same
 * transaction as a refund owed to them ({@link HeldRefunds}); lending asks PayPal after the commit and retries until it
 * is done. It is never kept and never counted as a payment.
 */
@Service
public class DisbursementReturn {

    private static final Logger log = LoggerFactory.getLogger(DisbursementReturn.class);

    private final LoanRepository loans;
    private final TransactionRepository transactions;
    private final TimerRepository timers;
    private final AttemptRepository attempts;
    private final LoanRecalculator recalculator;
    private final LedgerFacade ledger;
    private final GovernanceFacade governance;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;
    private final HeldRefunds heldRefunds;

    /** Without the refund book (a unit test with no database): the refund is still announced on the event, nothing is recorded. */
    public DisbursementReturn(LoanRepository loans, TransactionRepository transactions, TimerRepository timers,
                              AttemptRepository attempts, LoanRecalculator recalculator, LedgerFacade ledger,
                              GovernanceFacade governance, BusinessClock clock, ApplicationEventPublisher events) {
        this(loans, transactions, timers, attempts, recalculator, ledger, governance, clock, events, null);
    }

    @Autowired
    public DisbursementReturn(LoanRepository loans, TransactionRepository transactions, TimerRepository timers,
                              AttemptRepository attempts, LoanRecalculator recalculator, LedgerFacade ledger,
                              GovernanceFacade governance, BusinessClock clock, ApplicationEventPublisher events,
                              HeldRefunds heldRefunds) {
        this.loans = loans;
        this.transactions = transactions;
        this.timers = timers;
        this.attempts = attempts;
        this.recalculator = recalculator;
        this.ledger = ledger;
        this.governance = governance;
        this.clock = clock;
        this.events = events;
        this.heldRefunds = heldRefunds;
    }

    /**
     * Voids the loan of a {@code DISBURSED} application whose payout came back and changes the application in memory:
     * the caller saves it and announces the change, in the same transaction. Does nothing for an application that is
     * not disbursed.
     *
     * @return true when a loan was voided
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean unwind(LoanApplication app, String payoutReference, String reasonCode) {
        if (app.getStatus() != ApplicationStatus.DISBURSED || app.getLoanId() == null) {
            return false;
        }
        LoanRow loan = loans.lock(app.getLoanId()).orElse(null);
        if (loan == null || loan.isUnwound()) {
            return false;
        }
        LocalDate today = clock.today();
        Instant now = clock.now();

        // One entry: the disbursement comes back to program cash; the receivable is cleared; what the member paid is
        // held for them; what the fund paid goes back to the fund. Debits equal credits by construction.
        Settlement settled = settle(loan, "loan-void:" + loan.id(), "Loan disbursement returned by PayPal", today);
        long refundDue = settled.refundDueCents();
        long reserveCover = settled.reserveCoverCents();

        loans.updateDerived(loan.id(), LoanStatus.DISBURSEMENT_RETURNED, 0, refundDue, 0, loan.bucket(),
                loan.bucketSince(), today);
        timers.replacePlanned(loan.id(), Set.of());
        attempts.cancelPlanned(loan.id(), "loan voided");
        owe(loan, HeldRefunds.voidReference(loan.id()), refundDue);

        String reason = cleanReason(reasonCode);
        app.loanReturned(now);

        events.publishEvent(new LendingEvents.LoanVoided(Ids.newId(), loan.id(), loan.memberId(), today, app.getId(),
                payoutReference, reason, loan.principalCents(), refundDue, reserveCover));
        events.publishEvent(new LendingEvents.MandateSyncRequested(Ids.newId(), loan.id(), loan.memberId(), today));
        if (governance != null) {
            governance.audit(new AuditCommand("LOAN_DISBURSEMENT_RETURNED", loan.id(), "SYSTEM", "lending", auditPayload(app,
                    loan, payoutReference, reason, refundDue, reserveCover)));
        }
        log.warn("Payout {} of application {} came back ({}); loan {} voided, {} cents to refund to the member, {} cents back to the fund",
                payoutReference, app.getId(), reason, loan.id(), refundDue, reserveCover);
        return true;
    }

    /**
     * The member cancelled inside the cooling-off window and gives the money back. In the caller's transaction, with the
     * application locked first, this settles the books exactly as a voided payout does (the entry is keyed by the
     * application): the disbursement comes back to program cash, the receivable goes to exactly zero, what the member
     * had already paid is held for them and refunded, and what the loss reserve paid goes back to the fund. The loan
     * closes as {@code CANCELLED} with nothing owed, its timers and planned retries go, and
     * {@link LendingEvents.LoanVoided} (reason {@link LendingEvents.LoanVoided#COOLING_OFF_CANCELLED}) tells the record,
     * the desk, the reserve, reminders and hardship to drop it. A loan that is already voided or cancelled is left as it
     * is, so a replay does nothing.
     *
     * @return true when a loan was closed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean closeCancelled(LoanApplication app) {
        if (app.getLoanId() == null) {
            return false;
        }
        LoanRow loan = loans.lock(app.getLoanId()).orElse(null);
        if (loan == null || loan.isUnwound()) {
            return false;
        }
        LocalDate today = clock.today();
        Settlement settled = settle(loan, "loan-cooling-off:" + app.getId(), "48h cooling-off return for loan " + loan.id(), today);
        long refundDue = settled.refundDueCents();
        long reserveCover = settled.reserveCoverCents();

        loans.updateDerived(loan.id(), LoanStatus.CANCELLED, 0, refundDue, 0, loan.bucket(), loan.bucketSince(), today);
        timers.replacePlanned(loan.id(), Set.of());
        attempts.cancelPlanned(loan.id(), "cancelled in the cooling-off window");
        owe(loan, HeldRefunds.cancelReference(loan.id()), refundDue);
        events.publishEvent(new LendingEvents.LoanVoided(Ids.newId(), loan.id(), loan.memberId(), today, app.getId(),
                app.payoutReference(), LendingEvents.LoanVoided.COOLING_OFF_CANCELLED, loan.principalCents(), refundDue, reserveCover));
        events.publishEvent(new LendingEvents.MandateSyncRequested(Ids.newId(), loan.id(), loan.memberId(), today));
        if (governance != null) {
            governance.audit(new AuditCommand("LOAN_COOLING_OFF_CANCELLED", loan.id(), "MEMBER", loan.memberId().toString(),
                    "{\"applicationId\":\"" + app.getId() + "\",\"loanId\":\"" + loan.id() + "\",\"principalCents\":"
                            + loan.principalCents() + ",\"refundDueCents\":" + refundDue + ",\"reserveCoverCents\":" + reserveCover + "}"));
        }
        log.info("Loan {} of application {} closed: the member cancelled in the cooling-off window; {} cents to refund, {} back to the fund",
                loan.id(), app.getId(), refundDue, reserveCover);
        return true;
    }

    /** What an unwound loan's money comes to: what the member is owed back and what the fund gets back. */
    private record Settlement(long refundDueCents, long reserveCoverCents) {
    }

    /**
     * Posts the entry that undoes a loan, from what the schedule still owed ({@code O}), what the fund paid ({@code c})
     * and so what the member paid ({@code m = P - O - c}): debit program cash {@code P}; credit the receivable
     * {@code O}, the member's held overpayment {@code m} and the loss reserve {@code c}. The receivable therefore ends at
     * exactly zero and never below it, whoever paid what.
     */
    private Settlement settle(LoanRow loan, String sourceKey, String description, LocalDate today) {
        Allocation allocation = recalculator.project(loan, List.of()).allocation();
        long owed = allocation.outstandingCents();
        long heldAlready = allocation.overpaymentCents();
        long reserveCover = reserveCoverOf(loan.id());
        long paidByMember = loan.principalCents() - owed - reserveCover;
        if (paidByMember < 0) {
            throw new IllegalStateException("loan " + loan.id() + " has more reserve cover than it has had applied");
        }
        ledger.post(PostingRequest.builder(sourceKey, description, today)
                .debit(AccountRef.program(AccountType.PROGRAM_CASH), loan.principalCents())
                .credit(AccountRef.of(AccountType.LOANS_RECEIVABLE, loan.id()), owed)
                .credit(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, loan.memberId()), paidByMember)
                .credit(AccountRef.program(AccountType.LOSS_RESERVE), reserveCover)
                .build());
        return new Settlement(heldAlready + paidByMember, reserveCover);
    }

    private void owe(LoanRow loan, String reference, long refundDue) {
        if (heldRefunds != null && refundDue > 0) {
            heldRefunds.owe(loan.id(), loan.memberId(), reference, refundDue);
        }
    }

    /** What the loss reserve paid on this loan, as applied to the schedule. */
    private long reserveCoverOf(UUID loanId) {
        long sum = 0;
        for (LoanTransaction t : transactions.forLoan(loanId)) {
            if (t.source() == Source.RESERVE && t.type() == Type.RECEIPT) {
                sum += t.amountCents();
            }
        }
        return sum;
    }

    /** PayPal's code is a name, but it reaches the audit chain for good, so only plain characters pass. */
    private static String cleanReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "RETURNED";
        }
        String clean = reasonCode.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private static String auditPayload(LoanApplication app, LoanRow loan, String payoutReference, String reason,
                                       long refundDue, long reserveCover) {
        return "{\"applicationId\":\"" + app.getId() + "\",\"loanId\":\"" + loan.id() + "\",\"memberId\":\"" + loan.memberId()
                + "\",\"payoutReference\":\"" + cleanReason(payoutReference) + "\",\"reason\":\"" + reason
                + "\",\"principalCents\":" + loan.principalCents() + ",\"refundDueCents\":" + refundDue
                + ",\"reserveCoverCents\":" + reserveCover + "}";
    }
}
