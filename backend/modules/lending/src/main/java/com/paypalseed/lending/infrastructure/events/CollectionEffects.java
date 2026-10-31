package com.paypalseed.lending.infrastructure.events;

import com.paypalseed.lending.api.events.LendingEvents;
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

    CollectionEffects(PaymentsFacade payments, LoanQueries queries) {
        this.payments = payments;
        this.queries = queries;
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
        long refundable = payments.refundableCents(loan.id());
        if (refundable < loan.overpaidCents()) {
            // Retrying will not change this; the overpayment stays held and visible to the desk.
            log.error("Cannot auto-refund {} cents for loan {}: only {} cents of PayPal captures are refundable",
                    loan.overpaidCents(), loan.id(), refundable);
            return;
        }
        // A PayPal outage throws and leaves the event incomplete, so the refund is retried.
        payments.refundOverpayment(new RefundOverpayment(loan.id(), loan.overpaidCents(), "auto-refund:" + e.transactionId()));
        log.info("Auto-refunding {} cents overpaid on repaid loan {}", loan.overpaidCents(), loan.id());
    }
}
