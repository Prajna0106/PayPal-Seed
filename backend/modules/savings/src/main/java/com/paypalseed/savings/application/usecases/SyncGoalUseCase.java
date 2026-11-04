package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.savings.application.ports.CreditedPayments.Source;
import com.paypalseed.savings.application.ports.GoalStore;
import com.paypalseed.savings.application.ports.GoalSyncLog;
import com.paypalseed.savings.application.ports.WithdrawalStore;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.application.ports.SavingsPayments.PayoutReport;
import com.paypalseed.savings.application.ports.SavingsPayments.SaleReport;
import com.paypalseed.savings.application.ports.SavingsPayments.SubscriptionReport;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase.State;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.Withdrawal;
import com.paypalseed.savings.domain.model.WithdrawalStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings a goal up to date from PayPal when no webhook did (ADR 0028). A sandbox cannot call a developer's machine, and a
 * production delivery can be lost; either way the goal would wait for an approval or a deposit for ever. The facts PayPal
 * holds go through the steps the webhook listeners use, {@link SubscriptionStateUseCase} and {@link CreditPaymentUseCase},
 * so there is one path for a goal's state and one for crediting, both idempotent: a webhook that comes before, after or
 * twice changes nothing.
 *
 * <p>This class holds no transaction. Each step is its own: a deposit the goal cannot take (it was paused, closed or
 * cancelled meanwhile) rolls back alone and is left for the next read, because the sale stays in PayPal's window.
 */
public class SyncGoalUseCase {

    /** {@code read} is false when the goal was not read: not a goal with a live subscription, or read a moment ago. */
    public record Result(SavingsGoal goal, boolean read) {
    }

    private static final Logger log = LoggerFactory.getLogger(SyncGoalUseCase.class);

    private final GoalStore goals;
    private final SavingsPayments payments;
    private final GoalSyncLog syncLog;
    private final SubscriptionStateUseCase states;
    private final CreditPaymentUseCase credits;
    private final Clock realTime;
    private final Duration minGap;
    private final WithdrawalStore withdrawals;
    private final WithdrawalOutcomeUseCase outcomes;

    public SyncGoalUseCase(GoalStore goals, SavingsPayments payments, GoalSyncLog syncLog, SubscriptionStateUseCase states,
                           CreditPaymentUseCase credits, Clock realTime, Duration minGap) {
        this(goals, payments, syncLog, states, credits, realTime, minGap, null, null);
    }

    /** With the withdrawals, a refresh also reads the goal's payouts that PayPal has not reported yet (ADR 0029). */
    public SyncGoalUseCase(GoalStore goals, SavingsPayments payments, GoalSyncLog syncLog, SubscriptionStateUseCase states,
                           CreditPaymentUseCase credits, Clock realTime, Duration minGap, WithdrawalStore withdrawals,
                           WithdrawalOutcomeUseCase outcomes) {
        this.goals = Objects.requireNonNull(goals);
        this.payments = Objects.requireNonNull(payments);
        this.syncLog = Objects.requireNonNull(syncLog);
        this.states = Objects.requireNonNull(states);
        this.credits = Objects.requireNonNull(credits);
        this.realTime = Objects.requireNonNull(realTime);
        this.minGap = Objects.requireNonNull(minGap);
        this.withdrawals = withdrawals;
        this.outcomes = outcomes;
    }

    /** The goal as it is after the read; unchanged and unread when it has no live subscription or was read within the minimum gap. */
    public Result execute(UUID goalId) {
        SavingsGoal goal = goals.findById(goalId)
            .orElseThrow(() -> new DomainException(SavingsErrors.GOAL_NOT_FOUND, "Savings goal not found"));
        boolean read = hasLiveSubscription(goal) && syncLog.claim(goalId, realTime.instant(), minGap);
        if (read) {
            Optional<SubscriptionReport> report = payments.readSubscription(goalId);
            report.ifPresent(r -> apply(goalId, r));
        }
        boolean paidOut = syncWithdrawals(goalId);
        return new Result(read || paidOut ? goals.findById(goalId).orElse(goal) : goal, read);
    }

    /**
     * Reads the goal's withdrawals that are still on their way and applies what PayPal says through the steps the payout event
     * listener uses, so a payout whose webhook never came ends the withdrawal too. Best effort: a payout PayPal cannot be asked
     * about now is left for the next refresh or the payout reconcile and never fails the refresh of the goal.
     */
    private boolean syncWithdrawals(UUID goalId) {
        if (withdrawals == null || outcomes == null) {
            return false;
        }
        boolean changed = false;
        for (Withdrawal w : withdrawals.findByGoal(goalId)) {
            if (w.getStatus() != WithdrawalStatus.REQUESTED) {
                continue;
            }
            try {
                Optional<PayoutReport> report = payments.syncPayout(w.getId().toString());
                if (report.isPresent()) {
                    changed |= applyPayout(w.getId(), report.get());
                }
            } catch (RuntimeException e) {
                log.info("Payout of withdrawal {} not read yet: {}", w.getId(), e.toString());
            }
        }
        return changed;
    }

    private boolean applyPayout(UUID withdrawalId, PayoutReport report) {
        return switch (report.end()) {
            case SUCCEEDED -> outcomes.succeeded(withdrawalId, report.transactionId());
            case FAILED -> outcomes.failed(withdrawalId, report.reasonCode(), true);
            case RETURNED -> outcomes.returned(withdrawalId, report.reasonCode());
            case UNCLAIMED, PENDING -> false;
        };
    }

    private void apply(UUID goalId, SubscriptionReport report) {
        // State first: a goal still waiting for approval cannot take a deposit until the state report activates it.
        report.state().ifPresent(s -> states.apply(goalId, report.subscriptionId(), State.valueOf(s.state().name()), s.at()));
        for (SaleReport sale : report.sales()) {
            try {
                credits.credit(goalId, sale.transactionId(), sale.amountCents(), Source.SUBSCRIPTION);
            } catch (IllegalStateException e) {
                log.info("Sale {} of goal {} not credited yet: {}", sale.transactionId(), goalId, e.getMessage());
            }
        }
    }

    private static boolean hasLiveSubscription(SavingsGoal goal) {
        GoalStatus status = goal.getStatus();
        boolean live = status == GoalStatus.PENDING_APPROVAL || status == GoalStatus.ACTIVE || status == GoalStatus.PAUSED;
        return live && goal.getPayPalSubscriptionId() != null && !goal.getPayPalSubscriptionId().isBlank();
    }
}
