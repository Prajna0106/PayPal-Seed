package com.paypalseed.savings.api.dto;

import com.paypalseed.savings.domain.model.Withdrawal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Where a withdrawal stands. {@code REQUESTED}: the money is reserved and PayPal is paying it out;
 * {@code SUCCEEDED}: it arrived; {@code FAILED}: it did not and the money is back in the goal;
 * {@code RETURNED}: PayPal took it back and the money is in the goal again.
 */
public record WithdrawalResponse(
    UUID id,
    UUID goalId,
    String status,
    BigDecimal amount,
    String currency,
    String wallet,
    String failureReason,
    Instant createdAt,
    Instant updatedAt
) {
    public static WithdrawalResponse from(Withdrawal w) {
        return new WithdrawalResponse(w.getId(), w.getGoalId(), w.getStatus().name(), w.getAmount().amount(),
            w.getAmount().currency(), w.getWallet().name(), w.getFailureReason(), w.getCreatedAt(), w.getUpdatedAt());
    }
}
