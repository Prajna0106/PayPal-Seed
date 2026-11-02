package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.application.ports.SavingsPayments.Order;
import com.paypalseed.savings.application.ports.SavingsPayments.OrderCapture;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-off deposits through PayPal Orders: the only way to save from Venmo (PayPal Subscriptions cannot be
 * funded with it), and open to PayPal goals too. Starting an order moves no money; the member approves it at
 * PayPal and the goal is credited when PayPal reports the capture (an event), not when this runs.
 */
public class TopUpUseCase {

    private final SavingsGoalRepository goals;
    private final SavingsPayments payments;

    public TopUpUseCase(SavingsGoalRepository goals, SavingsPayments payments) {
        this.goals = Objects.requireNonNull(goals);
        this.payments = Objects.requireNonNull(payments);
    }

    /** Idempotent on {@code idempotencyKey}: the same key returns the same PayPal order. */
    @Transactional
    public Order start(UUID goalId, Money amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(SavingsErrors.IDEMPOTENCY_KEY_REQUIRED, "Send an Idempotency-Key header");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Top-up amount must be positive");
        }
        SavingsGoal goal = find(goalId);
        if (!goal.acceptsDeposits()) {
            throw new DomainException(SavingsErrors.TOPUP_NOT_ALLOWED,
                "A goal that is " + goal.getStatus().name().toLowerCase(java.util.Locale.ROOT) + " cannot take a top-up");
        }
        return payments.startTopUp(RecordDepositUseCase.toMemberUuid(goal.getMemberId()), goalId, amount.cents(),
            goal.getWallet(), idempotencyKey);
    }

    /** Captures the approved order. The goal is credited by the capture event, once, whoever reports it first. */
    @Transactional
    public OrderCapture capture(UUID goalId, String orderId) {
        find(goalId);
        return payments.captureTopUp(goalId, orderId);
    }

    private SavingsGoal find(UUID goalId) {
        return goals.findById(goalId)
            .orElseThrow(() -> new DomainException(SavingsErrors.GOAL_NOT_FOUND, "Savings goal not found: " + goalId));
    }
}
