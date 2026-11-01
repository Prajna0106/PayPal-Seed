package com.paypalseed.savings.infrastructure;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.application.SavingsFacadeImpl;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.ProcessWithdrawalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SavingsConfiguration {

    @Bean
    public CreateGoalUseCase createGoalUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        return new CreateGoalUseCase(repository, eventOutbox);
    }

    @Bean
    public RecordDepositUseCase recordDepositUseCase(
        SavingsGoalRepository repository,
        EventOutbox eventOutbox,
        @Autowired(required = false) LedgerFacade ledger,
        @Autowired(required = false) BusinessClock clock
    ) {
        return new RecordDepositUseCase(repository, eventOutbox, ledger, clock);
    }

    @Bean
    public ProcessWithdrawalUseCase processWithdrawalUseCase(
        SavingsGoalRepository repository,
        EventOutbox eventOutbox,
        @Autowired(required = false) LedgerFacade ledger,
        @Autowired(required = false) BusinessClock clock
    ) {
        return new ProcessWithdrawalUseCase(repository, eventOutbox, ledger, clock);
    }

    @Bean
    public PauseResumeGoalUseCase pauseResumeGoalUseCase(SavingsGoalRepository repository) {
        return new PauseResumeGoalUseCase(repository);
    }

    @Bean
    public SavingsFacade savingsFacade(
        RecordDepositUseCase recordDepositUseCase,
        PauseResumeGoalUseCase pauseResumeGoalUseCase,
        SavingsGoalRepository repository
    ) {
        return new SavingsFacadeImpl(recordDepositUseCase, pauseResumeGoalUseCase, repository);
    }
}
