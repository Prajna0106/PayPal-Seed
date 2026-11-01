package com.paypalseed.savings.infrastructure.persistence;

import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsGoal;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class SavingsRepository implements SavingsGoalRepository {

    private final Map<UUID, SavingsGoal> storage = new ConcurrentHashMap<>();

    @Override
    public SavingsGoal save(SavingsGoal goal) {
        Objects.requireNonNull(goal, "Goal cannot be null");
        storage.put(goal.getId(), goal);
        return goal;
    }

    @Override
    public Optional<SavingsGoal> findById(UUID id) {
        return Optional.ofNullable(storage.get(id));
    }

    @Override
    public List<SavingsGoal> findByMemberId(String memberId) {
        return storage.values().stream()
            .filter(g -> g.getMemberId().equalsIgnoreCase(memberId))
            .sorted(Comparator.comparing(SavingsGoal::getCreatedAt))
            .toList();
    }

    @Override
    public Optional<SavingsGoal> findByPayPalSubscriptionId(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.trim().isEmpty()) {
            return Optional.empty();
        }
        return storage.values().stream()
            .filter(g -> subscriptionId.equalsIgnoreCase(g.getPayPalSubscriptionId()))
            .findFirst();
    }

    @Override
    public long countActiveByMemberId(String memberId) {
        return storage.values().stream()
            .filter(g -> g.getMemberId().equalsIgnoreCase(memberId) && g.getStatus() == GoalStatus.ACTIVE)
            .count();
    }

    public void clear() {
        storage.clear();
    }
}
