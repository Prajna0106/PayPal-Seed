package com.paypalseed.savings.api.events;

import com.paypalseed.platform.kernel.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when PayPal took a withdrawal back (an unclaimed payout expired, or a delivered one was reversed):
 * the money is in the goal again, and the member should be told.
 */
public record WithdrawalReturnedEvent(
    UUID eventId,
    String memberId,
    UUID goalId,
    UUID withdrawalId,
    Money amount,
    String reasonCode,
    Instant timestamp
) {
    public static WithdrawalReturnedEvent now(String memberId, UUID goalId, UUID withdrawalId, Money amount, String reasonCode) {
        return new WithdrawalReturnedEvent(UUID.randomUUID(), memberId, goalId, withdrawalId, amount, reasonCode, Instant.now());
    }
}
