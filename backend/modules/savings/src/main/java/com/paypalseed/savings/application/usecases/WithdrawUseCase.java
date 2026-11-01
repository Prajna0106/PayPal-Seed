package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.ports.GoalStore;
import com.paypalseed.savings.application.ports.PayoutRecipients;
import com.paypalseed.savings.application.ports.PayoutRecipients.Recipient;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.application.ports.SavingsPayments.Payout;
import com.paypalseed.savings.application.ports.WithdrawalStore;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsWallet;
import com.paypalseed.savings.domain.model.Withdrawal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts a withdrawal: the amount leaves the balance for a reserve, then PayPal is asked to pay it out with the
 * withdrawal id as the payout reference. The books move when the payout succeeds, not now. The goal row is
 * locked for the whole step, so the same money cannot be reserved twice; a transient PayPal failure rolls the
 * reserve back with the request, and the member retries with the same Idempotency-Key.
 */
public class WithdrawUseCase {

    private final GoalStore goals;
    private final WithdrawalStore withdrawals;
    private final SavingsPayments payments;
    private final PayoutRecipients recipients;
    private final WithdrawalOutcomeUseCase outcomes;
    private final BusinessClock clock;

    public WithdrawUseCase(GoalStore goals, WithdrawalStore withdrawals, SavingsPayments payments, PayoutRecipients recipients,
                           WithdrawalOutcomeUseCase outcomes, BusinessClock clock) {
        this.goals = Objects.requireNonNull(goals);
        this.withdrawals = Objects.requireNonNull(withdrawals);
        this.payments = Objects.requireNonNull(payments);
        this.recipients = Objects.requireNonNull(recipients);
        this.outcomes = Objects.requireNonNull(outcomes);
        this.clock = Objects.requireNonNull(clock);
    }

    /** {@code wallet} is the member's choice for this payout; null means their saved wallet preference. */
    public record Command(UUID goalId, Money amount, SavingsWallet wallet, String idempotencyKey) {
    }

    @Transactional
    public Withdrawal execute(Command c) {
        if (c.idempotencyKey() == null || c.idempotencyKey().isBlank()) {
            throw new DomainException(SavingsErrors.IDEMPOTENCY_KEY_REQUIRED, "Send an Idempotency-Key header");
        }
        SavingsGoal goal = goals.findByIdForUpdate(c.goalId())
            .orElseThrow(() -> new DomainException(SavingsErrors.GOAL_NOT_FOUND, "Savings goal not found: " + c.goalId()));
        Recipient recipient = recipients.of(RecordDepositUseCase.toMemberUuid(goal.getMemberId())).orElse(null);
        SavingsWallet wallet = c.wallet() != null ? c.wallet()
            : recipient != null && recipient.preferredWallet() != null ? recipient.preferredWallet() : goal.getWallet();

        Optional<Withdrawal> earlier = withdrawals.findByGoalAndKey(goal.getId(), c.idempotencyKey());
        if (earlier.isPresent()) {
            return replay(earlier.get(), c.amount(), wallet);
        }
        if (recipient == null) {
            throw new DomainException(SavingsErrors.PAYOUT_RECIPIENT_MISSING, "Add a PayPal account to receive your savings");
        }
        Instant now = clock.now();
        goal.reserve(c.amount(), now);
        UUID id = UUID.nameUUIDFromBytes(("withdrawal:" + goal.getId() + ":" + c.idempotencyKey()).getBytes(StandardCharsets.UTF_8));
        Withdrawal withdrawal = Withdrawal.request(id, goal.getId(), goal.getMemberId(), c.idempotencyKey(), c.amount(), wallet, now);
        if (!withdrawals.insert(withdrawal)) {
            return replay(withdrawals.findByGoalAndKey(goal.getId(), c.idempotencyKey()).orElseThrow(), c.amount(), wallet);
        }
        goals.save(goal);

        Payout payout = payments.sendPayout(id.toString(), c.amount().cents(), recipient, wallet, "Seed savings withdrawal");
        switch (payout.state()) {
            case SUCCEEDED -> outcomes.succeeded(id, payout.transactionId());
            case FAILED -> outcomes.failed(id, payout.reasonCode(), true);
            case PENDING -> {
                // PayPal reports the end by webhook; the reserve holds the money until then
            }
        }
        return withdrawals.findByGoalAndKey(goal.getId(), c.idempotencyKey()).orElseThrow();
    }

    private static Withdrawal replay(Withdrawal earlier, Money amount, SavingsWallet wallet) {
        if (!earlier.matches(amount, wallet)) {
            throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key already started a different withdrawal");
        }
        return earlier;
    }
}
