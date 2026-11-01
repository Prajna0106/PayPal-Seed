package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.events.GoalCreatedEvent;
import com.paypalseed.savings.application.ports.GoalStore;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.application.ports.SavingsPayments.Subscription;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsWallet;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a goal and, for a PayPal wallet, the PayPal subscription behind it. The member has not agreed to
 * anything yet: the goal waits as {@code PENDING_APPROVAL} with the approval URL until PayPal reports the
 * activation, and expires if that never happens. Idempotent on the member's Idempotency-Key; the goal id
 * comes from it, so a retry after a timeout is the same goal and the same PayPal request.
 */
public class StartGoalUseCase {

    private final GoalStore goals;
    private final SavingsPayments payments;
    private final EventOutbox eventOutbox;
    private final BusinessClock clock;
    private final SavingsFlowSettings settings;

    public StartGoalUseCase(GoalStore goals, SavingsPayments payments, EventOutbox eventOutbox, BusinessClock clock,
                            SavingsFlowSettings settings) {
        this.goals = Objects.requireNonNull(goals);
        this.payments = Objects.requireNonNull(payments);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.clock = Objects.requireNonNull(clock);
        this.settings = Objects.requireNonNull(settings);
    }

    public record Command(String memberId, String name, Money targetAmount, Money monthlyAmount, LocalDate targetDate,
                          SavingsWallet wallet, String idempotencyKey) {
    }

    /** {@code replayed} is true when the key had already created this goal. */
    public record Started(SavingsGoal goal, boolean replayed) {
    }

    @Transactional
    public Started execute(Command c) {
        if (c.idempotencyKey() == null || c.idempotencyKey().isBlank()) {
            throw new DomainException(SavingsErrors.IDEMPOTENCY_KEY_REQUIRED, "Send an Idempotency-Key header");
        }
        SavingsWallet wallet = c.wallet() == null ? SavingsWallet.PAYPAL : c.wallet();
        if (wallet.supportsSubscriptions() && !c.monthlyAmount().isWholeDollars()) {
            throw new DomainException(SavingsErrors.AMOUNT_NOT_WHOLE_DOLLARS, "Choose a whole number of dollars each month");
        }
        var existing = goals.findByCreateKey(c.memberId(), c.idempotencyKey());
        if (existing.isPresent()) {
            return new Started(sameRequest(existing.get(), c, wallet), true);
        }
        long open = goals.countActiveByMemberId(c.memberId());
        if (open >= CreateGoalUseCase.MAX_ACTIVE_GOALS_PER_MEMBER) {
            throw new IllegalStateException("Maximum limit of " + CreateGoalUseCase.MAX_ACTIVE_GOALS_PER_MEMBER + " active goals reached");
        }
        Instant now = clock.now();
        UUID goalId = UUID.nameUUIDFromBytes(("goal:" + c.memberId() + ":" + c.idempotencyKey()).getBytes(StandardCharsets.UTF_8));
        SavingsGoal goal = SavingsGoal.createForSubscription(goalId, c.memberId(), c.name(), c.targetAmount(), c.monthlyAmount(),
            c.targetDate(), wallet, c.idempotencyKey(), now.plus(settings.approvalWindow()), now);
        if (wallet.supportsSubscriptions()) {
            Subscription subscription = payments.startSubscription(RecordDepositUseCase.toMemberUuid(c.memberId()), goalId,
                c.monthlyAmount().cents(), c.idempotencyKey(), settings.returnUrlFor(goalId), settings.cancelUrlFor(goalId));
            goal.attachSubscription(subscription.subscriptionId(), subscription.approvalUrl().orElse(null), now);
        }
        goals.save(goal);
        eventOutbox.publish(GoalCreatedEvent.now(goal.getMemberId(), goal.getId(), goal.getName(), goal.getMonthlyAmount(),
            goal.getTargetAmount()));
        return new Started(goal, false);
    }

    private static SavingsGoal sameRequest(SavingsGoal goal, Command c, SavingsWallet wallet) {
        boolean same = Objects.equals(goal.getName(), c.name() == null ? null : c.name().trim())
            && goal.getTargetAmount().equals(c.targetAmount()) && goal.getMonthlyAmount().equals(c.monthlyAmount())
            && goal.getWallet() == wallet && Objects.equals(goal.getTargetDate(), c.targetDate());
        if (!same) {
            throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key already created a different goal");
        }
        return goal;
    }
}
