package com.paypalseed.savings.infrastructure;

import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.ProcessWithdrawalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SavingsConfiguration {

    @Bean
    public CreateGoalUseCase createGoalUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        return new CreateGoalUseCase(repository, eventOutbox);
    }

    @Bean
    public RecordDepositUseCase recordDepositUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        return new RecordDepositUseCase(repository, eventOutbox);
    }

    @Bean
    public ProcessWithdrawalUseCase processWithdrawalUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        return new ProcessWithdrawalUseCase(repository, eventOutbox);
    }

    @Bean
    public PauseResumeGoalUseCase pauseResumeGoalUseCase(SavingsGoalRepository repository) {
        return new PauseResumeGoalUseCase(repository);
    }
}
