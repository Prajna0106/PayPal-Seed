package com.paypalseed.savings.infrastructure;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.api.SavingsCoachNarrator;
import com.paypalseed.savings.application.ports.CreditedPayments;
import com.paypalseed.savings.application.ports.GoalStore;
import com.paypalseed.savings.application.ports.PayoutRecipients;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.application.ports.WithdrawalStore;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.CreditPaymentUseCase;
import com.paypalseed.savings.application.usecases.ExpireApprovalsUseCase;
import com.paypalseed.savings.application.usecases.ManageGoalUseCase;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.ProcessWithdrawalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.application.usecases.SavingsFlowSettings;
import com.paypalseed.savings.application.usecases.StartGoalUseCase;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase;
import com.paypalseed.savings.application.usecases.SuggestSavingsPlanUseCase;
import com.paypalseed.savings.application.usecases.TopUpUseCase;
import com.paypalseed.savings.application.usecases.WithdrawUseCase;
import com.paypalseed.savings.application.usecases.WithdrawalOutcomeUseCase;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SavingsConfiguration {

    @Bean
    public CreateGoalUseCase createGoalUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        return new CreateGoalUseCase(repository, eventOutbox);
    }

    @Bean
    public SuggestSavingsPlanUseCase suggestSavingsPlanUseCase(
        @Autowired(required = false) SavingsCoachNarrator narrator
    ) {
        return new SuggestSavingsPlanUseCase(narrator);
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

    /**
     * Where PayPal sends the member after approving (or backing out of) a subscription, and how long a goal waits
     * for that. The defaults suit a local web app; deployments set {@code SEED_SAVINGS_RETURN_URL},
     * {@code SEED_SAVINGS_CANCEL_URL} and {@code SEED_SAVINGS_APPROVAL_WINDOW} (an ISO-8601 duration).
     */
    @Bean
    public SavingsFlowSettings savingsFlowSettings(
        @Value("${seed.savings.return-url:http://localhost:3000/app/savings/return}") String returnUrl,
        @Value("${seed.savings.cancel-url:http://localhost:3000/app/savings/cancelled}") String cancelUrl,
        @Value("${seed.savings.approval-window:PT24H}") Duration approvalWindow
    ) {
        return new SavingsFlowSettings(returnUrl, cancelUrl, approvalWindow);
    }

    @Bean
    public StartGoalUseCase startGoalUseCase(GoalStore goals, SavingsPayments payments, EventOutbox eventOutbox,
                                             BusinessClock clock, SavingsFlowSettings settings) {
        return new StartGoalUseCase(goals, payments, eventOutbox, clock, settings);
    }

    @Bean
    public ManageGoalUseCase manageGoalUseCase(GoalStore goals, SavingsPayments payments, BusinessClock clock,
                                               SavingsFlowSettings settings) {
        return new ManageGoalUseCase(goals, payments, clock, settings);
    }

    @Bean
    public TopUpUseCase topUpUseCase(SavingsGoalRepository goals, SavingsPayments payments) {
        return new TopUpUseCase(goals, payments);
    }

    @Bean
    public CreditPaymentUseCase creditPaymentUseCase(CreditedPayments credited, RecordDepositUseCase deposits, GoalStore goals) {
        return new CreditPaymentUseCase(credited, deposits, goals);
    }

    @Bean
    public SubscriptionStateUseCase subscriptionStateUseCase(GoalStore goals, SavingsPayments payments) {
        return new SubscriptionStateUseCase(goals, payments);
    }

    @Bean
    public WithdrawalOutcomeUseCase withdrawalOutcomeUseCase(GoalStore goals, WithdrawalStore withdrawals, EventOutbox eventOutbox,
                                                             LedgerFacade ledger, BusinessClock clock) {
        return new WithdrawalOutcomeUseCase(goals, withdrawals, eventOutbox, ledger, clock);
    }

    @Bean
    public WithdrawUseCase withdrawUseCase(GoalStore goals, WithdrawalStore withdrawals, SavingsPayments payments,
                                           PayoutRecipients recipients, WithdrawalOutcomeUseCase outcomes, BusinessClock clock) {
        return new WithdrawUseCase(goals, withdrawals, payments, recipients, outcomes, clock);
    }

    @Bean
    public ExpireApprovalsUseCase expireApprovalsUseCase(GoalStore goals, BusinessClock clock) {
        return new ExpireApprovalsUseCase(goals, clock);
    }

    // SavingsFacade is the @Service SavingsFacadeImpl; declaring a second bean of the type here made injection by type ambiguous.
}
