package com.paypalseed.payments.domain.model;

import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The paths a payout can take. PayPal reports out of order and more than once (a batch webhook, an
 * item webhook, a poll), so a report is applied only when it moves the payout forward. The same table
 * is enforced in the database by {@code payments.guard_payout}.
 */
public final class PayoutStateMachine {

    private static final Map<PayoutStatus, Set<PayoutStatus>> NEXT = Map.of(
            PayoutStatus.REQUESTED, Set.of(PayoutStatus.PENDING, PayoutStatus.SUCCEEDED, PayoutStatus.FAILED,
                    PayoutStatus.BLOCKED, PayoutStatus.UNCLAIMED, PayoutStatus.RETURNED),
            PayoutStatus.PENDING, Set.of(PayoutStatus.SUCCEEDED, PayoutStatus.FAILED, PayoutStatus.BLOCKED,
                    PayoutStatus.UNCLAIMED, PayoutStatus.RETURNED),
            PayoutStatus.UNCLAIMED, Set.of(PayoutStatus.SUCCEEDED, PayoutStatus.FAILED, PayoutStatus.RETURNED),
            PayoutStatus.SUCCEEDED, Set.of(PayoutStatus.RETURNED),
            PayoutStatus.FAILED, Set.of(),
            PayoutStatus.BLOCKED, Set.of(),
            PayoutStatus.RETURNED, Set.of());

    private PayoutStateMachine() {
    }

    public static boolean canMove(PayoutStatus from, PayoutStatus to) {
        return NEXT.get(from).contains(to);
    }

    /** The status to record for an observation, or empty when it repeats or contradicts what is known. */
    public static Optional<PayoutStatus> apply(PayoutStatus current, PayoutStatus observed) {
        return canMove(current, observed) ? Optional.of(observed) : Optional.empty();
    }

    /**
     * Maps PayPal's item {@code transaction_status} to ours. ONHOLD is still pending from the sender's view.
     * REFUNDED and REVERSED mean the money came back, so they read as RETURNED. Unknown words stay empty.
     */
    public static Optional<PayoutStatus> fromPaypalItem(String transactionStatus) {
        return switch (transactionStatus) {
            case "SUCCESS" -> Optional.of(PayoutStatus.SUCCEEDED);
            case "PENDING", "ONHOLD" -> Optional.of(PayoutStatus.PENDING);
            case "FAILED" -> Optional.of(PayoutStatus.FAILED);
            case "BLOCKED" -> Optional.of(PayoutStatus.BLOCKED);
            case "UNCLAIMED" -> Optional.of(PayoutStatus.UNCLAIMED);
            case "RETURNED", "REFUNDED", "REVERSED" -> Optional.of(PayoutStatus.RETURNED);
            default -> Optional.empty();
        };
    }

    /** A batch with no item yet: only the batch status tells us anything. */
    public static Optional<PayoutStatus> fromPaypalBatch(String batchStatus) {
        return switch (batchStatus) {
            case "DENIED", "CANCELED" -> Optional.of(PayoutStatus.FAILED);
            case "NEW", "PENDING", "PROCESSING" -> Optional.of(PayoutStatus.PENDING);
            default -> Optional.empty();
        };
    }
}
