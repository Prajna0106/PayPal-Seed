package com.paypalseed.lending.application.usecases;

import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.infrastructure.persistence.HeldRefundRepository;
import com.paypalseed.lending.infrastructure.persistence.HeldRefundRepository.HeldRefund;
import com.paypalseed.lending.infrastructure.persistence.HeldRefundRepository.Status;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundOverpayment;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundResult;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The refund of money held for a member on a loan that never stood: a payout that came back, or a cooling-off
 * cancellation (ADR 0024, closing pass). The member's money is held on the loan until PayPal has given it back, and
 * this class makes that promise durable.
 *
 * <ul>
 *   <li>{@link #owe} writes a pending refund in the transaction that holds the money, keyed by a reference that is also
 *       the refund's idempotency key, so the same refund can never be owed or made twice;</li>
 *   <li>{@link #attempt} asks PayPal once. The PayPal call and the "refunded" mark commit together or not at all, so a
 *       refund that happened is never repeated and one that did not stays pending. A failure is counted in its own
 *       transaction and the next try is scheduled with a growing delay;</li>
 *   <li>{@link #retryDue} (the worker's job) tries again whatever is due; after {@code max-attempts} the refund is
 *       given up: it becomes {@code FAILED}, the money stays held and visible on the loan, and a governance audit entry
 *       tells the desk. The member is told nothing, because nothing has changed for them.</li>
 * </ul>
 * Never more than is held: the refund is asked only while the loan still holds at least its amount.
 */
@Service
public class HeldRefunds {

    private static final Logger log = LoggerFactory.getLogger(HeldRefunds.class);

    static final int BATCH = 50;
    private static final Duration LONGEST_WAIT = Duration.ofHours(24);

    public enum Result {
        /** PayPal refunded and the refund is recorded as done. */
        REFUNDED,
        /** Nothing to do now: unknown reference, already finished, or not due yet. */
        NOTHING_TO_DO,
        /** The attempt failed and another is scheduled. */
        WILL_RETRY,
        /** No more attempts: the refund is {@code FAILED} and a person has to settle it. */
        GAVE_UP
    }

    private final HeldRefundRepository refunds;
    private final LoanRepository loans;
    private final PaymentsFacade payments;
    private final GovernanceFacade governance;
    private final BusinessClock clock;
    private final TransactionTemplate separate;
    private final TransactionTemplate readOnly;
    private final int maxAttempts;
    private final Duration retryDelay;

    public HeldRefunds(HeldRefundRepository refunds, LoanRepository loans, PaymentsFacade payments, GovernanceFacade governance,
                       BusinessClock clock, PlatformTransactionManager transactions,
                       @Value("${seed.lending.held-refund.max-attempts:6}") int maxAttempts,
                       @Value("${seed.lending.held-refund.retry-delay:PT15M}") Duration retryDelay) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("seed.lending.held-refund.max-attempts must be at least 1");
        }
        this.refunds = refunds;
        this.loans = loans;
        this.payments = payments;
        this.governance = governance;
        this.clock = clock;
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    /** The refund of what a voided loan holds for the member when the loan is voided. */
    public static String voidReference(UUID loanId) {
        return "void-refund:" + loanId;
    }

    /** The refund of a payment that reached a voided or cancelled loan; one payment, one refund. */
    public static String latePaymentReference(UUID transactionId) {
        return "void-refund:" + transactionId;
    }

    /** The refund of what a payment left over on a repaid loan; the same key the direct refund uses. */
    public static String autoRefundReference(UUID transactionId) {
        return "auto-refund:" + transactionId;
    }

    /** The refund of what a member had paid when they cancelled in the cooling-off window. */
    public static String cancelReference(UUID loanId) {
        return "cancel-refund:" + loanId;
    }

    /** The reference of the refund a {@link LendingEvents.LoanVoided} announces. */
    public static String referenceOf(LendingEvents.LoanVoided e) {
        return LendingEvents.LoanVoided.COOLING_OFF_CANCELLED.equals(e.reason()) ? cancelReference(e.loanId()) : voidReference(e.loanId());
    }

    /** Delay before attempt {@code attemptsMade + 1}: the base delay, doubled each time, never longer than a day. */
    static Duration backoff(Duration base, int attemptsMade) {
        long factor = 1L << Math.min(Math.max(attemptsMade - 1, 0), 20);
        Duration wait = base.multipliedBy(factor);
        return wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait;
    }

    /**
     * Records that {@code amountCents} held for the member on the loan must be refunded. Call it in the transaction that
     * holds the money. The same reference again changes nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void owe(UUID loanId, UUID memberId, String reference, long amountCents) {
        if (amountCents <= 0) {
            return;
        }
        if (refunds.insertIfAbsent(reference, loanId, memberId, amountCents, clock.now())) {
            log.info("Refund {} of {} cents owed to the member of loan {}", reference, amountCents, loanId);
        }
    }

    /**
     * Makes one attempt at the refund, if it is pending and due. Safe to call from anywhere and any number of times: the
     * refund row is locked for the attempt, and the idempotency key is the reference.
     */
    public Result attempt(String reference) {
        try {
            return separate.execute(status -> refundNow(reference));
        } catch (RuntimeException e) {
            return separate.execute(status -> recordFailure(reference, e));
        }
    }

    /** The worker's retry: every pending refund whose time has come. @return how many were refunded */
    public int retryDue() {
        List<String> due = readOnly.execute(status -> refunds.dueReferences(clock.now(), BATCH));
        int refunded = 0;
        for (String reference : due == null ? List.<String>of() : due) {
            try {
                if (attempt(reference) == Result.REFUNDED) {
                    refunded++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not record the outcome of refund {}: {}", reference, e.getClass().getSimpleName());
            }
        }
        return refunded;
    }

    private Result refundNow(String reference) {
        HeldRefund row = refunds.lock(reference).orElse(null);
        if (row == null || row.status() != Status.PENDING || row.nextAttemptAt().isAfter(clock.now())) {
            return Result.NOTHING_TO_DO;
        }
        LoanRow loan = loans.find(row.loanId()).orElseThrow(() -> new IllegalStateException("unknown loan " + row.loanId()));
        if (loan.overpaidCents() == 0) {
            // The member took the money another way (the refund endpoint of a void loan); nothing is left to give back.
            refunds.markRefunded(reference, "ALREADY_SETTLED");
            log.info("Refund {} of loan {} found nothing held; it was settled another way", reference, loan.id());
            return Result.NOTHING_TO_DO;
        }
        if (loan.overpaidCents() < row.amountCents()) {
            // Not a PayPal problem and retrying cannot fix it: never refund more than is held.
            giveUp(row, row.attempts(), "HELD_LESS_THAN_REFUND");
            return Result.GAVE_UP;
        }
        // The PayPal call joins this transaction; if it fails the exception rolls everything back and attempt() counts it.
        RefundResult result = payments.refundOverpayment(new RefundOverpayment(loan.id(), row.amountCents(), reference));
        refunds.markRefunded(reference, String.join(",", result.paypalRefundIds()));
        log.info("Refunded {} cents held for loan {} ({})", row.amountCents(), loan.id(), reference);
        return Result.REFUNDED;
    }

    private Result recordFailure(String reference, RuntimeException failure) {
        HeldRefund row = refunds.lock(reference).orElse(null);
        if (row == null || row.status() != Status.PENDING) {
            return Result.NOTHING_TO_DO;
        }
        int attempts = row.attempts() + 1;
        String error = describe(failure);
        if (attempts >= maxAttempts) {
            giveUp(row, attempts, error);
            return Result.GAVE_UP;
        }
        refunds.recordFailure(reference, attempts, error, Status.PENDING, clock.now().plus(backoff(retryDelay, attempts)));
        log.warn("Refund {} failed (attempt {} of {}): {}", reference, attempts, maxAttempts, error);
        return Result.WILL_RETRY;
    }

    private void giveUp(HeldRefund row, int attempts, String error) {
        refunds.recordFailure(row.reference(), attempts, error, Status.FAILED, row.nextAttemptAt());
        if (governance != null) {
            governance.audit(new AuditCommand("HELD_REFUND_FAILED", row.loanId(), "SYSTEM", "lending",
                    "{\"reference\":\"" + row.reference() + "\",\"loanId\":\"" + row.loanId() + "\",\"memberId\":\"" + row.memberId()
                            + "\",\"amountCents\":" + row.amountCents() + ",\"attempts\":" + attempts + ",\"reason\":\"" + error + "\"}"));
        }
        log.error("Gave up refunding {} cents held for loan {} ({}) after {} attempts: {}; the money stays held until a person settles it",
                row.amountCents(), row.loanId(), row.reference(), attempts, error);
    }

    /** A code, never a message: the text reaches the audit chain and the database for good. */
    private static String describe(RuntimeException failure) {
        if (failure instanceof DomainException e) {
            return e.errorCode().code();
        }
        return failure.getClass().getSimpleName();
    }
}
