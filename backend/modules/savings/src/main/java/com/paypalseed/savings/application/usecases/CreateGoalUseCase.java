package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.api.events.GoalCreatedEvent;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.SavingsGoal;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

public class CreateGoalUseCase {

    public static final int MAX_ACTIVE_GOALS_PER_MEMBER = 3;

    private final SavingsGoalRepository repository;
    private final EventOutbox eventOutbox;

    public CreateGoalUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        this.repository = Objects.requireNonNull(repository);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
    }

    public record Command(
        String memberId,
        String name,
        Money targetAmount,
        Money monthlyAmount,
        LocalDate targetDate,
        String payPalSubscriptionId
    ) {}

    /**
     * Owns its transaction: the goal and its {@code GoalCreatedEvent} commit together, and the event reaches the
     * {@code @ApplicationModuleListener}s only because it is published inside one.
     */
    @Transactional
    public SavingsGoal execute(Command command) {
        long activeCount = repository.countActiveByMemberId(command.memberId());
        if (activeCount >= MAX_ACTIVE_GOALS_PER_MEMBER) {
            throw new IllegalStateException("Maximum limit of " + MAX_ACTIVE_GOALS_PER_MEMBER + " active goals reached");
        }

        SavingsGoal goal = SavingsGoal.create(
            command.memberId(),
            command.name(),
            command.targetAmount(),
            command.monthlyAmount(),
            command.targetDate(),
            command.payPalSubscriptionId()
        );

        SavingsGoal saved = repository.save(goal);

        eventOutbox.publish(GoalCreatedEvent.now(
            saved.getMemberId(),
            saved.getId(),
            saved.getName(),
            saved.getMonthlyAmount(),
            saved.getTargetAmount()
        ));

        return saved;
    }
}
