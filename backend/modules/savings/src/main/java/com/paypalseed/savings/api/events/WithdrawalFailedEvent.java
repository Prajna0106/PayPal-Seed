package com.paypalseed.savings.api.events;

import com.paypalseed.platform.kernel.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a withdrawal's payout failed for good: PayPal could not deliver it, the reserved money is back
 * in the goal, and the member should be told. {@code reasonCode} is PayPal's reason name, never free text.
 */
public record WithdrawalFailedEvent(
    UUID eventId,
    String memberId,
    UUID goalId,
    UUID withdrawalId,
    Money amount,
    String reasonCode,
    Instant timestamp
) {
    public static WithdrawalFailedEvent now(String memberId, UUID goalId, UUID withdrawalId, Money amount, String reasonCode) {
        return new WithdrawalFailedEvent(UUID.randomUUID(), memberId, goalId, withdrawalId, amount, reasonCode, Instant.now());
    }
}
