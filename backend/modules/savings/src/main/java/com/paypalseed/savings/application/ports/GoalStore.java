package com.paypalseed.savings.application.ports;

import com.paypalseed.savings.domain.model.SavingsGoal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What the PayPal-backed flows need beyond plain load and save: row locks, replay lookup and the approval sweep. */
public interface GoalStore extends SavingsGoalRepository {

    /** The goal a member created with this Idempotency-Key, if any. */
    Optional<SavingsGoal> findByCreateKey(String memberId, String createKey);

    /** Goals still waiting for approval whose window closed at or before {@code now}, oldest first. */
    List<UUID> findApprovalsExpiredBy(Instant now, int limit);
}
