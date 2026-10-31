package com.paypalseed.savings.api.dto;

import com.paypalseed.savings.domain.model.SavingsGoal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SavingsGoalResponse(
    UUID id,
    String memberId,
    String name,
    BigDecimal targetAmount,
    BigDecimal monthlyAmount,
    BigDecimal currentBalance,
    String currency,
    String status,
    LocalDate targetDate,
    String payPalSubscriptionId,
    Instant createdAt,
    Instant updatedAt
) {
    public static SavingsGoalResponse from(SavingsGoal goal) {
        return new SavingsGoalResponse(
            goal.getId(),
            goal.getMemberId(),
            goal.getName(),
            goal.getTargetAmount().amount(),
            goal.getMonthlyAmount().amount(),
            goal.getCurrentBalance().amount(),
            goal.getMonthlyAmount().currency(),
            goal.getStatus().name(),
            goal.getTargetDate(),
            goal.getPayPalSubscriptionId(),
            goal.getCreatedAt(),
            goal.getUpdatedAt()
        );
    }
}
