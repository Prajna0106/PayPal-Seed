package com.paypalseed.savings.application.usecases;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.api.events.WithdrawalFailedEvent;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import com.paypalseed.savings.api.events.WithdrawalReturnedEvent;
import com.paypalseed.savings.application.ports.GoalStore;
import com.paypalseed.savings.application.ports.WithdrawalStore;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.Withdrawal;
import com.paypalseed.savings.domain.model.WithdrawalStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * The end of a withdrawal, as PayPal reports it. Each method is idempotent: a payout event delivered twice
 * (or the inline answer followed by the webhook) moves the withdrawal once. The ledger is touched only when
 * money really left: debit the member's savings and credit program cash at success, the reverse if PayPal
 * takes a delivered payout back. While the payout is in flight the books are untouched and balanced; the
 * goal's reserve is what stops the money being spent twice.
 */
public class WithdrawalOutcomeUseCase {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalOutcomeUseCase.class);

    private final GoalStore goals;
    private final WithdrawalStore withdrawals;
    private final EventOutbox eventOutbox;
    private final LedgerFacade ledger;
    private final BusinessClock clock;

    public WithdrawalOutcomeUseCase(GoalStore goals, WithdrawalStore withdrawals, EventOutbox eventOutbox, LedgerFacade ledger,
                                    BusinessClock clock) {
        this.goals = Objects.requireNonNull(goals);
        this.withdrawals = Objects.requireNonNull(withdrawals);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.ledger = Objects.requireNonNull(ledger);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The money reached the member. Returns false when the reference is not a withdrawal or was already completed. */
    @Transactional
    public boolean succeeded(UUID withdrawalId, String paypalTransactionId) {
        Optional<Withdrawal> found = withdrawals.findByIdForUpdate(withdrawalId);
        if (found.isEmpty()) {
            return false;
        }
        Withdrawal w = found.get();
        Instant now = clock.now();
        if (!w.moveTo(WithdrawalStatus.SUCCEEDED, null, paypalTransactionId, now)) {
            return false;
        }
        SavingsGoal goal = lockedGoal(w);
        goal.settleReserve(w.getAmount(), now);
        goals.save(goal);
        withdrawals.update(w);
        ledger.post(PostingRequest.builder("savings-withdrawal:" + goal.getId() + ":" + w.getId(),
                "Savings withdrawal from goal " + goal.getId(), clock.today())
            .debit(AccountRef.of(AccountType.MEMBER_SAVINGS, RecordDepositUseCase.toMemberUuid(goal.getMemberId())), w.getAmount().cents())
            .credit(AccountRef.program(AccountType.PROGRAM_CASH), w.getAmount().cents())
            .build());
        eventOutbox.publish(WithdrawalProcessedEvent.now(goal.getMemberId(), goal.getId(), w.getAmount(),
            paypalTransactionId != null ? paypalTransactionId : w.getId().toString()));
        return true;
    }

    /**
     * The payout failed. A non-terminal failure (money held at PayPal for a recipient without an account) changes
     * nothing: the reserve stays and a success or a return follows.
     */
    @Transactional
    public boolean failed(UUID withdrawalId, String reasonCode, boolean terminal) {
        if (!terminal) {
            return false;
        }
        Optional<Withdrawal> found = withdrawals.findByIdForUpdate(withdrawalId);
        if (found.isEmpty()) {
            return false;
        }
        Withdrawal w = found.get();
        Instant now = clock.now();
        if (!w.moveTo(WithdrawalStatus.FAILED, reasonCode == null ? "FAILED" : reasonCode, null, now)) {
            return false;
        }
        SavingsGoal goal = lockedGoal(w);
        goal.releaseReserve(w.getAmount(), now);
        goals.save(goal);
        withdrawals.update(w);
        eventOutbox.publish(WithdrawalFailedEvent.now(goal.getMemberId(), goal.getId(), w.getId(), w.getAmount(), w.getFailureReason()));
        return true;
    }

    /** PayPal took the money back: an unclaimed payout expired, or a delivered one was reversed. */
    @Transactional
    public boolean returned(UUID withdrawalId, String reasonCode) {
        Optional<Withdrawal> found = withdrawals.findByIdForUpdate(withdrawalId);
        if (found.isEmpty()) {
            return false;
        }
        Withdrawal w = found.get();
        boolean wasDelivered = w.getStatus() == WithdrawalStatus.SUCCEEDED;
        Instant now = clock.now();
        if (!w.moveTo(WithdrawalStatus.RETURNED, reasonCode == null ? "RETURNED" : reasonCode, null, now)) {
            return false;
        }
        SavingsGoal goal = lockedGoal(w);
        if (wasDelivered) {
            goal.restoreWithdrawn(w.getAmount(), now);
            ledger.post(PostingRequest.builder("savings-withdrawal-return:" + goal.getId() + ":" + w.getId(),
                    "Savings withdrawal returned to goal " + goal.getId(), clock.today())
                .debit(AccountRef.program(AccountType.PROGRAM_CASH), w.getAmount().cents())
                .credit(AccountRef.of(AccountType.MEMBER_SAVINGS, RecordDepositUseCase.toMemberUuid(goal.getMemberId())), w.getAmount().cents())
                .build());
        } else {
            goal.releaseReserve(w.getAmount(), now); // never delivered: nothing was booked, only the reserve to give back
        }
        goals.save(goal);
        withdrawals.update(w);
        eventOutbox.publish(WithdrawalReturnedEvent.now(goal.getMemberId(), goal.getId(), w.getId(), w.getAmount(), w.getFailureReason()));
        return true;
    }

    private SavingsGoal lockedGoal(Withdrawal w) {
        return goals.findByIdForUpdate(w.getGoalId()).orElseThrow(() -> {
            log.error("Withdrawal {} points at goal {} which does not exist", w.getId(), w.getGoalId());
            return new IllegalStateException("Goal " + w.getGoalId() + " of withdrawal " + w.getId() + " is missing");
        });
    }
}
