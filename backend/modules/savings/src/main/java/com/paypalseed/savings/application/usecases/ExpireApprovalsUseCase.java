package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.savings.application.ports.GoalStore;
import com.paypalseed.savings.domain.model.SavingsGoal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Closes goals whose member never approved the PayPal subscription within the window. */
public class ExpireApprovalsUseCase {

    private static final int BATCH = 100;

    private final GoalStore goals;
    private final BusinessClock clock;

    public ExpireApprovalsUseCase(GoalStore goals, BusinessClock clock) {
        this.goals = Objects.requireNonNull(goals);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Returns how many goals expired. Each goal is re-read under its lock, so a goal approved a moment ago is left alone. */
    @Transactional
    public int expireDue() {
        Instant now = clock.now();
        List<UUID> due = goals.findApprovalsExpiredBy(now, BATCH);
        int expired = 0;
        for (UUID id : due) {
            SavingsGoal goal = goals.findByIdForUpdate(id).orElse(null);
            if (goal != null && goal.approvalExpired(now)) {
                goal.expireApproval(now);
                goals.save(goal);
                expired++;
            }
        }
        return expired;
    }
}
