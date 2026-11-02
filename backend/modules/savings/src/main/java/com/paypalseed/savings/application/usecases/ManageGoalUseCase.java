package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.ports.GoalStore;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.application.ports.SavingsPayments.Subscription;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pause, resume and change the amount of a goal, keeping the PayPal subscription in step: pausing suspends it,
 * resuming activates it (or, when PayPal has ended it, starts a new one the member has to approve), and a new
 * amount revises its quantity. A goal that has no subscription at PayPal (Venmo, or one created with a client
 * supplied id in development) changes locally only. Every PayPal call carries a request id built from the goal's
 * change counter, so a retried request is the same call and a later one is a new call.
 */
public class ManageGoalUseCase {

    private final GoalStore goals;
    private final SavingsPayments payments;
    private final BusinessClock clock;
    private final SavingsFlowSettings settings;

    public ManageGoalUseCase(GoalStore goals, SavingsPayments payments, BusinessClock clock, SavingsFlowSettings settings) {
        this.goals = Objects.requireNonNull(goals);
        this.payments = Objects.requireNonNull(payments);
        this.clock = Objects.requireNonNull(clock);
        this.settings = Objects.requireNonNull(settings);
    }

    @Transactional
    public SavingsGoal pause(UUID goalId) {
        SavingsGoal goal = lock(goalId);
        boolean wasPaused = goal.getStatus() == GoalStatus.PAUSED;
        goal.pause();
        goal.noteSubscriptionState(Instant.now()); // PayPal stamps real time, so does this; the business clock may run ahead
        if (!wasPaused && hasSubscription(goal)) {
            payments.suspend(goalId, requestId(goal), "Paused by the member");
        }
        return goals.save(goal);
    }

    @Transactional
    public SavingsGoal resume(UUID goalId) {
        SavingsGoal goal = lock(goalId);
        Subscription current = goal.getStatus() == GoalStatus.PAUSED ? subscriptionOf(goal) : null;
        goal.noteSubscriptionState(Instant.now()); // a SUSPENDED report from before this resume must not pause the goal again
        if (current == null) {
            goal.resume();
        } else if (hasEnded(current)) {
            // PayPal cannot bring a cancelled subscription back; asking would fail and poison the transaction.
            startNewApproval(goal);
        } else {
            payments.activate(goalId, requestId(goal), "Resumed by the member");
            goal.resume();
        }
        return goals.save(goal);
    }

    /** Takes effect for the next deposit. PayPal may ask the member to agree first; the URL is on the goal. */
    @Transactional
    public SavingsGoal changeMonthlyAmount(UUID goalId, Money newMonthly) {
        SavingsGoal goal = lock(goalId);
        GoalStatus status = goal.getStatus();
        if (status == GoalStatus.PENDING_APPROVAL || status == GoalStatus.CANCELLED || status == GoalStatus.EXPIRED) {
            throw new IllegalStateException("The amount of a goal that is " + status.name().toLowerCase(java.util.Locale.ROOT)
                + " cannot be changed");
        }
        if (goal.getWallet().supportsSubscriptions() && !newMonthly.isWholeDollars()) {
            throw new DomainException(SavingsErrors.AMOUNT_NOT_WHOLE_DOLLARS, "Choose a whole number of dollars each month");
        }
        Instant now = clock.now();
        goal.changeMonthlyAmount(newMonthly, now);
        if (hasSubscription(goal)) {
            payments.revise(goal.getId(), newMonthly.cents(), requestId(goal))
                .ifPresent(url -> goal.attachSubscription(goal.getPayPalSubscriptionId(), url, now));
        }
        return goals.save(goal);
    }

    private void startNewApproval(SavingsGoal goal) {
        Instant now = clock.now();
        Subscription fresh = payments.startSubscription(RecordDepositUseCase.toMemberUuid(goal.getMemberId()), goal.getId(),
            goal.getMonthlyAmount().cents(), "renew:" + requestId(goal), settings.returnUrlFor(goal.getId()),
            settings.cancelUrlFor(goal.getId()));
        goal.attachSubscription(fresh.subscriptionId(), fresh.approvalUrl().orElse(null), now);
        goal.requireNewApproval(now.plus(settings.approvalWindow()), now);
    }

    private SavingsGoal lock(UUID goalId) {
        return goals.findByIdForUpdate(goalId)
            .orElseThrow(() -> new DomainException(SavingsErrors.GOAL_NOT_FOUND, "Savings goal not found: " + goalId));
    }

    /** A goal has a subscription Seed made when payments knows one; a client supplied legacy id is not Seed's to change. */
    private boolean hasSubscription(SavingsGoal goal) {
        return subscriptionOf(goal) != null;
    }

    private Subscription subscriptionOf(SavingsGoal goal) {
        if (!goal.getWallet().supportsSubscriptions() || goal.getPayPalSubscriptionId() == null) {
            return null;
        }
        return payments.subscriptionOf(goal.getId()).orElse(null);
    }

    private static boolean hasEnded(Subscription s) {
        return "CANCELLED".equals(s.status()) || "EXPIRED".equals(s.status());
    }

    private static String requestId(SavingsGoal goal) {
        return goal.getId() + ":" + goal.nextChange();
    }
}
