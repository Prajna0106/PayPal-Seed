package com.paypalseed.savings.application;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import org.springframework.stereotype.Service;

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
}
