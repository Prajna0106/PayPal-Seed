package com.paypalseed.savings.application.usecases;

import com.paypalseed.savings.application.ports.GoalStore;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsGoal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * What PayPal says about a goal's subscription becomes the goal's state: approved means the goal can take
 * deposits; suspended, cancelled or expired (by the member in PayPal, by PayPal, or by Seed) pauses it. Only
 * the goal's current subscription counts, so a late report about an old one never touches a new one, and a
 * report that matches what the goal already is changes nothing.
 *
 * <p>PayPal does not deliver webhooks in order, so a report is also ignored when it happened before a state the goal
 * already knows (a SUSPENDED that was overtaken by a later resume must not pause the goal again). The goal keeps the
 * time of the latest state; Seed's own pause and resume count as states too. A cancelled or expired subscription
 * never comes back, so those two are applied whatever their time.
 */
public class SubscriptionStateUseCase {

    public enum State {
        ACTIVE,
        SUSPENDED,
        CANCELLED,
        EXPIRED
    }

    private static final Logger log = LoggerFactory.getLogger(SubscriptionStateUseCase.class);

    private final GoalStore goals;
    private final SavingsPayments payments;

    public SubscriptionStateUseCase(GoalStore goals, SavingsPayments payments) {
        this.goals = Objects.requireNonNull(goals);
        this.payments = Objects.requireNonNull(payments);
    }

    @Transactional
    public void apply(UUID goalId, String subscriptionId, State state, Instant at) {
        Optional<SavingsGoal> found = goals.findByIdForUpdate(goalId);
        if (found.isEmpty()) {
            log.warn("Subscription {} reported {} for unknown goal {}", subscriptionId, state, goalId);
            return;
        }
        SavingsGoal goal = found.get();
        if (!subscriptionId.equals(goal.getPayPalSubscriptionId())) {
            return; // an earlier subscription of this goal
        }
        // Cancelled and expired are final (PayPal cannot bring them back), so only suspended and active need ordering.
        boolean canBeUndone = state == State.ACTIVE || state == State.SUSPENDED;
        if (canBeUndone && goal.subscriptionReportIsStale(at)) {
            log.info("Ignoring {} for subscription {}: it happened at {}, before the state the goal already knows ({})",
                state, subscriptionId, at, goal.getSubscriptionStateAt());
            return;
        }
        boolean changed = goal.noteSubscriptionState(at);
        switch (state) {
            case ACTIVE -> changed |= approved(goal, at);
            case SUSPENDED, CANCELLED, EXPIRED -> changed |= goal.pauseBecauseSubscriptionEnded(at);
        }
        if (changed) {
            goals.save(goal);
        }
    }

    /** True if the goal changed. */
    private boolean approved(SavingsGoal goal, Instant at) {
        if (goal.activate(at)) {
            return true;
        }
        if (goal.getStatus() == GoalStatus.EXPIRED || goal.getStatus() == GoalStatus.CANCELLED) {
            // The member approved after the window closed. Deposits would reach a goal that is closed: stop the billing.
            payments.cancel(goal.getId(), "late-approval:" + goal.getId(), "The goal is closed");
        }
        return false;
    }
}
