package com.paypalseed.savings.application;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsStreak;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class SavingsFacadeImpl implements SavingsFacade {

    private final RecordDepositUseCase recordDepositUseCase;
    private final PauseResumeGoalUseCase pauseResumeGoalUseCase;
    private final SavingsGoalRepository repository;

    public SavingsFacadeImpl(
        RecordDepositUseCase recordDepositUseCase,
        PauseResumeGoalUseCase pauseResumeGoalUseCase,
        SavingsGoalRepository repository
    ) {
        this.recordDepositUseCase = Objects.requireNonNull(recordDepositUseCase);
        this.pauseResumeGoalUseCase = Objects.requireNonNull(pauseResumeGoalUseCase);
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public void recordDepositBySubscription(String subscriptionId, Money amount, String payPalCaptureId) {
        recordDepositUseCase.executeForSubscription(subscriptionId, amount, payPalCaptureId);
    }

    @Override
    public void pauseGoalBySubscription(String subscriptionId) {
        Optional<SavingsGoal> goal = repository.findByPayPalSubscriptionId(subscriptionId);
        goal.ifPresent(g -> pauseResumeGoalUseCase.pause(g.getId()));
    }

    @Override
    public MemberSavingsSummary getMemberSavingsSummary(String memberId) {
        List<SavingsGoal> goals = repository.findByMemberId(memberId);
        if (goals.isEmpty()) {
            return new MemberSavingsSummary(memberId, 0, 0, 0, 0L, 0L, false);
        }

        long monthlySavingCents = 0L;
        long totalSavedBalanceCents = 0L;
        int bestDepositStreak = 0;
        int totalOnTimeDeposits = 0;

        for (SavingsGoal goal : goals) {
            totalSavedBalanceCents += goal.getCurrentBalance().cents();
            if (goal.getStatus() == GoalStatus.ACTIVE || goal.getStatus() == GoalStatus.COMPLETED) {
                monthlySavingCents += goal.getMonthlyAmount().cents();
                int deposits = goal.getDepositCount();
                totalOnTimeDeposits += deposits;
                if (deposits > bestDepositStreak) {
                    bestDepositStreak = deposits;
                }
            }
        }

        boolean eligible = bestDepositStreak >= SavingsStreak.MIN_MONTHS_FOR_MICROLOAN
            && totalOnTimeDeposits >= SavingsStreak.MIN_MONTHS_FOR_MICROLOAN;

        return new MemberSavingsSummary(
            memberId,
            bestDepositStreak,
            totalOnTimeDeposits,
            0,
            monthlySavingCents,
            totalSavedBalanceCents,
            eligible
        );
    }
}
