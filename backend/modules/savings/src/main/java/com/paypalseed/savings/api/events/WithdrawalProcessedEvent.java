package com.paypalseed.savings.api.events;

import com.paypalseed.platform.kernel.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Published whenever a member withdraws money from a savings goal back to PayPal or Venmo.
 * Consumed by: Ledger (posts debit Member Savings, credit Program Cash).
 */
public record WithdrawalProcessedEvent(
    UUID eventId,
    String memberId,
    UUID goalId,
    Money amount,
    String payPalPayoutId,
    Instant timestamp
) {
    public static WithdrawalProcessedEvent now(String memberId, UUID goalId, Money amount, String payPalPayoutId) {
        return new WithdrawalProcessedEvent(UUID.randomUUID(), memberId, goalId, amount, payPalPayoutId, Instant.now());
    }
}
