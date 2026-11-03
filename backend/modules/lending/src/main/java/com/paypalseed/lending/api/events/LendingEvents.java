package com.paypalseed.lending.api.events;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Domain events published by lending (02-design §7). Each is written to the Modulith event
 * publication registry in the same transaction as the state change it describes (transactional
 * outbox), and delivered at least once; consumers dedupe on {@code eventId}.
 */
public final class LendingEvents {

    private LendingEvents() {
    }

    public sealed interface LendingEvent permits ScheduleGenerated, ScheduleChanged, RepaymentReceived, RepaymentReversed,
            OverpaymentRefunded, RepaymentFailed, LateBucketChanged, InstalmentMissed, LoanCured, LoanRepaid,
            LoanReopened, InstalmentDueSoon, GraceNudgeDue, CollectionModeChanged, MandateSyncRequested,
            RetryCaptureRequested, CollectionCheckRequested, LoanVoided, VoidedLoanPaymentHeld {
        UUID eventId();

        UUID loanId();

        UUID memberId();

        LocalDate businessDate();
    }

    public record InstalmentView(int seq, LocalDate dueDate, long amountCents) {
    }

    public record ScheduleGenerated(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                    int version, List<InstalmentView> instalments) implements LendingEvent {
    }

    public record ScheduleChanged(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                  int previousVersion, int version, String reason, String causedBy,
                                  List<InstalmentView> instalments) implements LendingEvent {
    }

    /** {@code appliedCents} reduced the balance; {@code excessCents} is held as overpayment. */
    public record RepaymentReceived(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                    UUID transactionId, String source, long amountCents, long appliedCents,
                                    long excessCents, long outstandingCents, String loanStatus) implements LendingEvent {
    }

    public record RepaymentReversed(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                    UUID transactionId, UUID reversedTransactionId, long amountCents,
                                    long outstandingCents) implements LendingEvent {
    }

    public record OverpaymentRefunded(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                      UUID transactionId, long amountCents) implements LendingEvent {
    }

    public record RepaymentFailed(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                  int seq, LocalDate dueDate, long amountCents, int attemptNo, String reason,
                                  LocalDate retryPlannedOn) implements LendingEvent {
    }

    public record LateBucketChanged(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                    String fromBucket, String toBucket, int dpd, String cause) implements LendingEvent {
    }

    /**
     * @param attribution null when no hold was involved; {@code MEMBER} when a grace hold ended and the member still
     *                    had not paid; {@code SLA_BREACH} when our own decision on a hardship case ran late (ADR 0011).
     *                    A miss attributed to {@code SLA_BREACH} must not count against the member.
     */
    public record InstalmentMissed(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                   int version, int seq, LocalDate dueDate, String attribution) implements LendingEvent {
        /** A miss with no hold involved. */
        public InstalmentMissed(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate, int version, int seq,
                                LocalDate dueDate) {
            this(eventId, loanId, memberId, businessDate, version, seq, dueDate, null);
        }
    }

    public record LoanCured(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                            String fromBucket) implements LendingEvent {
    }

    /** {@code reserveCoveredCents} is how much of the loan the loss reserve paid (repayment source {@code RESERVE}). */
    public record LoanRepaid(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                             int instalmentsOnPlan, int instalmentsMissed, long overpaidCents,
                             long reserveCoveredCents) implements LendingEvent {
        /** A loan the reserve paid nothing on. */
        public LoanRepaid(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate, int instalmentsOnPlan,
                          int instalmentsMissed, long overpaidCents) {
            this(eventId, loanId, memberId, businessDate, instalmentsOnPlan, instalmentsMissed, overpaidCents, 0);
        }
    }

    public record LoanReopened(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                               long outstandingCents) implements LendingEvent {
    }

    public record InstalmentDueSoon(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                    int version, int seq, LocalDate dueDate, long amountCents,
                                    String collectionMode) implements LendingEvent {
    }

    public record GraceNudgeDue(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                int version, int seq, LocalDate dueDate, long overdueCents) implements LendingEvent {
    }

    public record CollectionModeChanged(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                        String fromMode, String toMode, String cause) implements LendingEvent {
    }

    /** Internal: bring the PayPal autopay subscription in line with the schedule (ADR 0005). */
    public record MandateSyncRequested(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate)
            implements LendingEvent {
    }

    /**
     * The payout that funded the loan was taken back after the loan was registered, so the loan is void (ADR 0024): it
     * is closed with status {@code DISBURSEMENT_RETURNED}, owes nothing, plans no timer or collection, and must not
     * count as a loan, a payment history or a default for anyone. A consumer that kept state for the loan from
     * {@link ScheduleGenerated} drops it. Published once per voided loan, inside the transaction that voided it.
     *
     * @param applicationId     the application that is approved again, ready for a new disbursement
     * @param payoutReference   the PayPal payout reference that came back
     * @param reason            PayPal's reason code, or {@code RETURNED}
     * @param principalCents    what the loan was for; the disbursement entry in the ledger was reversed in full
     * @param refundDueCents    what the member paid in before the return, held to be refunded to them (a refund is asked
     *                          of PayPal after the commit); zero when they had paid nothing
     * @param reserveCoverCents what the loss reserve had paid on the loan, returned to the fund in the same ledger entry
     */
    public record LoanVoided(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate, UUID applicationId,
                             String payoutReference, String reason, long principalCents, long refundDueCents,
                             long reserveCoverCents) implements LendingEvent {

        /**
         * The {@code reason} when the member cancelled inside the 48-hour cooling-off window instead: the loan is closed as
         * {@code CANCELLED} (not {@code DISBURSEMENT_RETURNED}), the application stays {@code CANCELLED_COOLING_OFF}, and
         * the member chose it, so no "payout came back" message or payout issue applies. Refund and cover are what the
         * member had paid in and what the loss reserve had paid, exactly as for a voided payout.
         */
        public static final String COOLING_OFF_CANCELLED = "COOLING_OFF_CANCELLED";
    }

    /**
     * Money from the member arrived for a loan that never stood (an autopay charge or a checkout in flight when the
     * payout came back, or a payment after the cooling-off cancel). All of it is held for the member and a refund is
     * asked of PayPal. It is not a repayment
     * and must not count on a credit record.
     */
    public record VoidedLoanPaymentHeld(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                        UUID transactionId, long amountCents, long heldCents) implements LendingEvent {
    }

    /** Internal: ask PayPal for the single automatic retry of a failed autopay charge. */
    public record RetryCaptureRequested(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                        UUID attemptId, long amountCents) implements LendingEvent {
    }

    /** Internal: no payment or failure arrived for an autopay due date, so ask PayPal what happened. */
    public record CollectionCheckRequested(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                           LocalDate dueDate) implements LendingEvent {
    }
}
