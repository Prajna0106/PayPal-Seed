package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.SavingsGoal;

import java.util.Objects;
import java.util.UUID;

public class ProcessWithdrawalUseCase {

    private final SavingsGoalRepository repository;
    private final EventOutbox eventOutbox;

    public ProcessWithdrawalUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        this.repository = Objects.requireNonNull(repository);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
    }

    public record Command(
        UUID goalId,
        Money amount,
        String payPalPayoutId
    ) {}

    public SavingsGoal execute(Command command) {
        SavingsGoal goal = repository.findById(command.goalId())
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found: " + command.goalId()));

        goal.recordWithdrawal(command.amount());
        SavingsGoal saved = repository.save(goal);

        eventOutbox.publish(WithdrawalProcessedEvent.now(
            saved.getMemberId(),
            saved.getId(),
            command.amount(),
            command.payPalPayoutId()
        ));

        return saved;
    }
}
