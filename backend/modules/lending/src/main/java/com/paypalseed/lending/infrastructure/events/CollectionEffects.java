package com.paypalseed.lending.infrastructure.events;

import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.HeldRefunds;
import com.paypalseed.lending.application.usecases.LoanQueries;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundOverpayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns lending's decisions into PayPal actions, after the decision has committed. Keeping PayPal
 * calls out of lending transactions means a slow or failing PayPal never holds a loan's row lock.
 */
@Component
class CollectionEffects {

    private static final Logger log = LoggerFactory.getLogger(CollectionEffects.class);

    private final PaymentsFacade payments;
    private final LoanQueries queries;
    private final HeldRefunds heldRefunds;

    CollectionEffects(PaymentsFacade payments, LoanQueries queries, HeldRefunds heldRefunds) {
        this.payments = payments;
        this.queries = queries;
        this.heldRefunds = heldRefunds;
    }

    /** ADR 0005: recompute the desired mandate from fresh state, so a burst of requests converges. */
    @ApplicationModuleListener
    void on(LendingEvents.MandateSyncRequested e) {
        payments.reconcileMandate(e.loanId(), queries.collectionSpec(e.loanId()));
    }

    @ApplicationModuleListener
    void on(LendingEvents.RetryCaptureRequested e) {
        payments.retryCapture(e.loanId(), e.attemptId(), e.amountCents());
    }

    @ApplicationModuleListener
    void on(LendingEvents.CollectionCheckRequested e) {
        payments.checkRecentPayments(e.loanId(), e.dueDate().minusDays(2));
    }

    /**
     * ADR 0024: what the member had paid on a loan that never stood (payout returned, or cancelled in the cooling-off
     * window) goes back to them. The refund was written down in the transaction that held the money; this is its first
     * attempt, and {@link HeldRefunds#retryDue} makes the later ones, so a PayPal that cannot refund yet does not lose it.
     */
    @ApplicationModuleListener
    void on(LendingEvents.LoanVoided e) {
        heldRefunds.attempt(HeldRefunds.referenceOf(e));
    }

    /** ADR 0024: a payment that reached a loan that never stood goes back to the member, one refund per payment. */
    @ApplicationModuleListener
    void on(LendingEvents.VoidedLoanPaymentHeld e) {
        heldRefunds.attempt(HeldRefunds.latePaymentReference(e.transactionId()));
    }

    /** FR-08 AC2: money received after a loan is repaid goes back to the member automatically. */
    @ApplicationModuleListener
    void on(LendingEvents.RepaymentReceived e) {
        if (!LoanStatus.REPAID.name().equals(e.loanStatus()) || e.excessCents() == 0) {
            return;
        }
        LoanRow loan = queries.loan(e.loanId());
        if (loan.status() != LoanStatus.REPAID || loan.overpaidCents() == 0) {
            return;
        }
        String reference = HeldRefunds.autoRefundReference(e.transactionId());
        long refundable = payments.refundableCents(loan.id());
        if (refundable < loan.overpaidCents()) {
            // Retrying the event cannot change this, so the refund becomes a held-refund row: the worker retries it with
            // backoff, and if PayPal never has enough it ends FAILED with a governance entry instead of only a log line.
            heldRefunds.owe(loan.id(), loan.memberId(), reference, loan.overpaidCents());
            log.warn("Cannot auto-refund {} cents for loan {} yet: only {} cents of PayPal captures are refundable; kept as refund {}",
                    loan.overpaidCents(), loan.id(), refundable, reference);
            return;
        }
        // A PayPal outage throws and leaves the event incomplete, so the refund is retried.
        payments.refundOverpayment(new RefundOverpayment(loan.id(), loan.overpaidCents(), reference));
        log.info("Auto-refunding {} cents overpaid on repaid loan {}", loan.overpaidCents(), loan.id());
    }
}
