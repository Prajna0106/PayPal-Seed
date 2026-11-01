package com.paypalseed.savings.application.usecases;

import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.SavingsGoal;

import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class PauseResumeGoalUseCase {

    private final SavingsGoalRepository repository;

    public PauseResumeGoalUseCase(SavingsGoalRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional
    public SavingsGoal pause(UUID goalId) {
        SavingsGoal goal = repository.findByIdForUpdate(goalId)
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found: " + goalId));

        goal.pause();
        return repository.save(goal);
    }

    @Transactional
    public SavingsGoal resume(UUID goalId) {
        SavingsGoal goal = repository.findByIdForUpdate(goalId)
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found: " + goalId));

        goal.resume();
        return repository.save(goal);
    }
}
