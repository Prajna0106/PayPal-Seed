package com.paypalseed.savings.application.ports;

import com.paypalseed.savings.domain.model.SavingsGoal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavingsGoalRepository {
    SavingsGoal save(SavingsGoal goal);
    Optional<SavingsGoal> findById(UUID id);

    /**
     * Loads the goal and, in a database, locks its row until the transaction ends, so two requests that
     * change the balance cannot both act on the old value. A store with no locks just loads it.
     */
    default Optional<SavingsGoal> findByIdForUpdate(UUID id) {
        return findById(id);
    }
    List<SavingsGoal> findByMemberId(String memberId);
    Optional<SavingsGoal> findByPayPalSubscriptionId(String subscriptionId);
    long countActiveByMemberId(String memberId);
}
