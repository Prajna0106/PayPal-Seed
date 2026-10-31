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
            RetryCaptureRequested, CollectionCheckRequested {
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

    public record InstalmentMissed(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                   int version, int seq, LocalDate dueDate) implements LendingEvent {
    }

    public record LoanCured(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                            String fromBucket) implements LendingEvent {
    }

    public record LoanRepaid(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                             int instalmentsOnPlan, int instalmentsMissed, long overpaidCents) implements LendingEvent {
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

    /** Internal: ask PayPal for the single automatic retry of a failed autopay charge. */
    public record RetryCaptureRequested(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                        UUID attemptId, long amountCents) implements LendingEvent {
    }

    /** Internal: no payment or failure arrived for an autopay due date, so ask PayPal what happened. */
    public record CollectionCheckRequested(UUID eventId, UUID loanId, UUID memberId, LocalDate businessDate,
                                           LocalDate dueDate) implements LendingEvent {
    }
}
