package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.MandateReconciliation.DueAmount;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.CapturedOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CheckoutOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CreateOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.MandateView;
import com.paypalseed.payments.api.dto.PaymentsDtos.OrderPurpose;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundOverpayment;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundResult;
import com.paypalseed.payments.api.dto.PaymentsDtos.StartMandate;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How money is collected for a loan: autopay enrolment (FR-03), one-off payments (FR-05) and
 * overpayment refunds (FR-08). PayPal calls happen outside lending transactions; money facts come
 * back as payment events and are recorded by {@link RepaymentService}.
 */
@Service
public class CollectionService {

    private final LoanRepository loans;
    private final LoanQueries queries;
    private final LoanRecalculator recalculator;
    private final PaymentsFacade payments;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public CollectionService(LoanRepository loans, LoanQueries queries, LoanRecalculator recalculator,
                             PaymentsFacade payments, BusinessClock clock, ApplicationEventPublisher events) {
        this.loans = loans;
        this.queries = queries;
        this.recalculator = recalculator;
        this.payments = payments;
        this.clock = clock;
        this.events = events;
    }

    /** Starts autopay; the first charge is the next instalment still owed that is not yet past due. */
    public MandateView startAutopay(LoanRow loan, String returnUrl, String cancelUrl) {
        requireActive(loan);
        if (loan.collectionMode() == CollectionMode.AUTOPAY) {
            throw new DomainException(LendingErrors.AUTOPAY_ALREADY_ACTIVE, "Autopay is already on");
        }
        DueAmount next = queries.collectionSpec(loan.id()).unpaid().stream()
                .filter(d -> !d.dueDate().isBefore(clock.today()))
                .findFirst()
                .orElseThrow(() -> new DomainException(LendingErrors.LOAN_NOT_ACTIVE, "Nothing left to collect by autopay"));
        return payments.startMandate(new StartMandate(loan.id(), loan.memberId(), next.dueDate(),
                next.remainingCents() / 100, returnUrl, cancelUrl));
    }

    /**
     * Return-URL confirmation; the activation webhook may arrive first, either order is fine. The
     * loan switches to autopay when payments publishes {@code MandateActivated}, exactly once.
     */
    public MandateView confirmAutopay(LoanRow loan, String subscriptionId) {
        return payments.confirmMandate(loan.id(), subscriptionId);
    }

    /** The loan returns to manual payments when payments publishes {@code MandateEnded}. */
    public void stopAutopay(LoanRow loan) {
        payments.cancelMandate(loan.id(), "Member switched to manual payments");
    }

    /** Idempotent: switching to the mode a loan already has changes nothing. */
    @Transactional
    public void switchMode(UUID loanId, CollectionMode mode, String cause) {
        LoanRow loan = loans.lock(loanId).orElseThrow(() -> new DomainException(LendingErrors.LOAN_NOT_FOUND, "No loan " + loanId));
        if (loan.collectionMode() == mode) {
            return;
        }
        loans.setCollectionMode(loanId, mode);
        LoanRow updated = loans.lock(loanId).orElseThrow();
        recalculator.recalculate(updated, clock.today(), LoanRecalculator.Cause.PAYMENT); // watchdog timers follow the mode
        events.publishEvent(new LendingEvents.CollectionModeChanged(Ids.newId(), loanId, loan.memberId(), clock.today(),
                loan.collectionMode().name(), mode.name(), cause));
        if (mode == CollectionMode.AUTOPAY) {
            events.publishEvent(new LendingEvents.MandateSyncRequested(Ids.newId(), loanId, loan.memberId(), clock.today()));
        }
    }

    /** FR-05: catch-up, early repayment, payoff or pay-what-you-can, in whole dollars up to the outstanding balance. */
    public CheckoutOrder createPayment(LoanRow loan, long amountCents, OrderPurpose purpose, String idempotencyKey) {
        requireActive(loan);
        if (amountCents < 100) {
            throw new DomainException(LendingErrors.AMOUNT_TOO_SMALL, "Minimum payment is $1");
        }
        if (amountCents % 100 != 0) {
            throw new DomainException(LendingErrors.AMOUNT_NOT_WHOLE_DOLLARS, "Pay in whole dollars",
                    Map.of("amountCents", amountCents));
        }
        if (amountCents > loan.outstandingCents()) {
            throw new DomainException(LendingErrors.AMOUNT_EXCEEDS_OUTSTANDING, "Outstanding is " + loan.outstandingCents(),
                    Map.of("outstandingCents", loan.outstandingCents()));
        }
        return payments.createOrder(new CreateOrder(loan.id(), purpose, amountCents, idempotencyKey));
    }

    public CapturedOrder capturePayment(LoanRow loan, String orderId) {
        return payments.captureOrder(loan.id(), orderId);
    }

    public RefundResult refundOverpayment(LoanRow loan, long amountCents, String idempotencyKey) {
        if (loan.overpaidCents() == 0) {
            throw new DomainException(LendingErrors.NO_OVERPAYMENT, "No overpayment is held for this loan");
        }
        if (amountCents > loan.overpaidCents()) {
            throw new DomainException(LendingErrors.REFUND_EXCEEDS_OVERPAYMENT, "Overpayment held is " + loan.overpaidCents(),
                    Map.of("overpaidCents", loan.overpaidCents()));
        }
        return payments.refundOverpayment(new RefundOverpayment(loan.id(), amountCents, idempotencyKey));
    }

    private static void requireActive(LoanRow loan) {
        if (!loan.isActive()) {
            throw new DomainException(LendingErrors.LOAN_NOT_ACTIVE, "Loan is " + loan.status());
        }
    }
}
