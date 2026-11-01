package com.paypalseed.savings.application.usecases;

import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.SavingsGoal;

import java.util.Objects;
import java.util.UUID;

public class PauseResumeGoalUseCase {

    private final SavingsGoalRepository repository;

    public PauseResumeGoalUseCase(SavingsGoalRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public SavingsGoal pause(UUID goalId) {
        SavingsGoal goal = repository.findById(goalId)
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found: " + goalId));

        goal.pause();
        return repository.save(goal);
    }

    public SavingsGoal resume(UUID goalId) {
        SavingsGoal goal = repository.findById(goalId)
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found: " + goalId));

        goal.resume();
        return repository.save(goal);
    }
}
