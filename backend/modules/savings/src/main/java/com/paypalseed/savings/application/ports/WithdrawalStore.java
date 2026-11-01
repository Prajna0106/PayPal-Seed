package com.paypalseed.savings.application.ports;

import com.paypalseed.savings.domain.model.Withdrawal;
import java.util.Optional;
import java.util.UUID;

public interface WithdrawalStore {

    /** False when the (goal, key) pair already exists: a concurrent request with the same Idempotency-Key won. */
    boolean insert(Withdrawal withdrawal);

    Optional<Withdrawal> findByGoalAndKey(UUID goalId, String idempotencyKey);

    /** Loads the withdrawal and locks it, so a payout event and a replay never move it at the same time. */
    Optional<Withdrawal> findByIdForUpdate(UUID id);

    java.util.List<Withdrawal> findByGoal(UUID goalId);

    void update(Withdrawal withdrawal);
}
