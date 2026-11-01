package com.paypalseed.savings.api.events;

import com.paypalseed.platform.kernel.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Published whenever a member successfully deposits money into a savings goal.
 * Consumed by: Ledger (posts debit Program Cash, credit Member Savings) and Streak calculations.
 */
public record DepositReceivedEvent(
    UUID eventId,
    String memberId,
    UUID goalId,
    Money amount,
    String payPalCaptureId,
    Instant timestamp
) {
    public static DepositReceivedEvent now(String memberId, UUID goalId, Money amount, String payPalCaptureId) {
        return new DepositReceivedEvent(UUID.randomUUID(), memberId, goalId, amount, payPalCaptureId, Instant.now());
    }
}
