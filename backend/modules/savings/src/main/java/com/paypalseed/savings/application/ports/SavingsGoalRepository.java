package com.paypalseed.savings.application.ports;

import com.paypalseed.savings.domain.model.SavingsGoal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavingsGoalRepository {
    SavingsGoal save(SavingsGoal goal);
    Optional<SavingsGoal> findById(UUID id);
    List<SavingsGoal> findByMemberId(String memberId);
    Optional<SavingsGoal> findByPayPalSubscriptionId(String subscriptionId);
    long countActiveByMemberId(String memberId);
}
