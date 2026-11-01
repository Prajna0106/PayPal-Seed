package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.SavingsGoal;

import java.util.Objects;
import java.util.UUID;

public class RecordDepositUseCase {

    private final SavingsGoalRepository repository;
    private final EventOutbox eventOutbox;

    public RecordDepositUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        this.repository = Objects.requireNonNull(repository);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
    }

    public record Command(
        UUID goalId,
        Money amount,
        String payPalCaptureId
    ) {}

    public SavingsGoal execute(Command command) {
        SavingsGoal goal = repository.findById(command.goalId())
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found: " + command.goalId()));

        goal.recordDeposit(command.amount(), command.payPalCaptureId());
        SavingsGoal saved = repository.save(goal);

        eventOutbox.publish(DepositReceivedEvent.now(
            saved.getMemberId(),
            saved.getId(),
            command.amount(),
            command.payPalCaptureId()
        ));

        return saved;
    }

    public SavingsGoal executeForSubscription(String subscriptionId, Money amount, String payPalCaptureId) {
        SavingsGoal goal = repository.findByPayPalSubscriptionId(subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found for PayPal subscription: " + subscriptionId));

        goal.recordDeposit(amount, payPalCaptureId);
        SavingsGoal saved = repository.save(goal);

        eventOutbox.publish(DepositReceivedEvent.now(
            saved.getMemberId(),
            saved.getId(),
            amount,
            payPalCaptureId
        ));

        return saved;
    }
}
