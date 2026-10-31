package com.paypalseed.lending.infrastructure.events;

import com.paypalseed.lending.application.usecases.CollectionFailureService;
import com.paypalseed.lending.application.usecases.CollectionService;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.payments.api.events.PaymentEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The lending side of the one ingestion funnel (FR-06): every normalised payment fact becomes a
 * lending transaction. Listeners run after the publishing transaction commits, in their own
 * transaction; a failure leaves the publication incomplete so it is retried (at-least-once), and
 * the PayPal transaction id makes each retry a no-op once recorded (exactly-once effect).
 */
@Component
class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final RepaymentService repayments;
    private final CollectionFailureService failures;
    private final CollectionService collection;

    PaymentEventsListener(RepaymentService repayments, CollectionFailureService failures, CollectionService collection) {
        this.repayments = repayments;
        this.failures = failures;
        this.collection = collection;
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PaymentCaptured e) {
        repayments.recordReceipt(new RepaymentService.Receipt(e.loanId(), e.paypalTxnId(), Source.valueOf(e.source().name()),
                e.grossCents(), e.feeCents(), e.occurredAt(), e.payerRef(), e.causedBy()));
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PaymentPending e) {
        repayments.recordPending(e.loanId(), e.paypalTxnId(), e.amountCents(), e.occurredAt());
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PaymentFailed e) {
        if (e.source() == PaymentEvents.Source.AUTOPAY || e.source() == PaymentEvents.Source.AUTOPAY_RETRY) {
            failures.recordFailure(e.loanId(), e.source() == PaymentEvents.Source.AUTOPAY_RETRY, e.reason(), e.causedBy());
        } else {
            log.info("One-off payment failed for loan {}: {}", e.loanId(), e.reason());
        }
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PaymentReversed e) {
        repayments.recordReversal(new RepaymentService.Reversal(e.loanId(), e.paypalTxnId(), e.originalTxnId(),
                e.amountCents(), e.occurredAt(), e.causedBy()));
    }

    @ApplicationModuleListener
    void on(PaymentEvents.OverpaymentRefundCompleted e) {
        repayments.recordOverpaymentRefund(new RepaymentService.OverpaymentRefund(e.loanId(), e.paypalRefundId(),
                e.amountCents(), e.occurredAt()));
    }

    @ApplicationModuleListener
    void on(PaymentEvents.MandateActivated e) {
        collection.switchMode(e.loanId(), CollectionMode.AUTOPAY, "MANDATE_ACTIVATED");
    }

    @ApplicationModuleListener
    void on(PaymentEvents.MandateEnded e) {
        collection.switchMode(e.loanId(), CollectionMode.MANUAL, "MANDATE_ENDED_BY_" + e.cause());
    }
}
