package com.paypalseed.savings.api.dto;

import com.paypalseed.savings.domain.model.SavingsGoal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A goal as the member sees it. {@code approvalUrl} is set while the goal is {@code PENDING_APPROVAL}: the member
 * has to open it at PayPal and agree before anything can be deposited. {@code reservedAmount} is money in a
 * withdrawal that has not finished; it is not in {@code currentBalance}.
 */
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
    Instant updatedAt,
    String wallet,
    String approvalUrl,
    Instant approvalExpiresAt,
    BigDecimal reservedAmount
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
            goal.getUpdatedAt(),
            goal.getWallet().name(),
            goal.getApprovalUrl(),
            goal.getApprovalExpiresAt(),
            goal.getReservedBalance().amount()
        );
    }
}
