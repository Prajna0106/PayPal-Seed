package com.paypalseed.savings.infrastructure;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.api.SavingsCoachNarrator;
import com.paypalseed.savings.application.ports.CreditedPayments;
import com.paypalseed.savings.application.ports.GoalStore;
import com.paypalseed.savings.application.ports.GoalSyncLog;
import com.paypalseed.savings.application.ports.PayoutRecipients;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.application.ports.WithdrawalStore;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.CreditPaymentUseCase;
import com.paypalseed.savings.application.usecases.ExpireApprovalsUseCase;
import com.paypalseed.savings.application.usecases.GoalVisibility;
import com.paypalseed.savings.application.usecases.ManageGoalUseCase;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.ProcessWithdrawalUseCase;
import com.paypalseed.savings.application.usecases.ReconcileGoalsUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.application.usecases.SavingsFlowSettings;
import com.paypalseed.savings.application.usecases.StartGoalUseCase;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase;
import com.paypalseed.savings.application.usecases.SuggestSavingsPlanUseCase;
import com.paypalseed.savings.application.usecases.SyncGoalUseCase;
import com.paypalseed.savings.application.usecases.TopUpUseCase;
import com.paypalseed.savings.application.usecases.WithdrawUseCase;
import com.paypalseed.savings.application.usecases.WithdrawalOutcomeUseCase;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
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
                                               ObjectProvider<Clock> realTime, SavingsFlowSettings settings) {
        return new ManageGoalUseCase(goals, payments, clock, realTime.getIfAvailable(Clock::systemUTC), settings);
    }

    @Bean
    public TopUpUseCase topUpUseCase(SavingsGoalRepository goals, SavingsPayments payments) {
        return new TopUpUseCase(goals, payments);
    }

    /**
     * How long a report that names a missing goal is delivered again before the goal is taken as never going to exist
     * ({@code SEED_SAVINGS_GOAL_VISIBILITY_WINDOW}, an ISO-8601 duration; ADR 0027).
     */
    @Bean
    public GoalVisibility goalVisibility(ObjectProvider<Clock> realTime,
                                         @Value("${seed.savings.goal-visibility-window:PT1H}") Duration window) {
        return new GoalVisibility(realTime.getIfAvailable(Clock::systemUTC), window);
    }

    @Bean
    public CreditPaymentUseCase creditPaymentUseCase(CreditedPayments credited, RecordDepositUseCase deposits, GoalStore goals,
                                                     GoalVisibility visibility) {
        return new CreditPaymentUseCase(credited, deposits, goals, visibility);
    }

    @Bean
    public SubscriptionStateUseCase subscriptionStateUseCase(GoalStore goals, SavingsPayments payments, GoalVisibility visibility) {
        return new SubscriptionStateUseCase(goals, payments, visibility);
    }

    /**
     * Reading a goal's PayPal subscription ourselves (ADR 0028): at most once per goal in {@code sync-min-interval}
     * ({@code SEED_SAVINGS_SYNC_MIN_INTERVAL}); the reconcile reads an active or paused goal again after
     * {@code reconcile-gap} ({@code SEED_SAVINGS_RECONCILE_GAP}). Real time, like every time PayPal dates.
     */
    @Bean
    public SyncGoalUseCase syncGoalUseCase(GoalStore goals, SavingsPayments payments, GoalSyncLog syncLog,
                                           SubscriptionStateUseCase states, CreditPaymentUseCase credits,
                                           ObjectProvider<Clock> realTime,
                                           @Value("${seed.savings.sync-min-interval:PT5S}") Duration minInterval) {
        return new SyncGoalUseCase(goals, payments, syncLog, states, credits, realTime.getIfAvailable(Clock::systemUTC), minInterval);
    }

    @Bean
    public ReconcileGoalsUseCase reconcileGoalsUseCase(GoalSyncLog syncLog, SyncGoalUseCase sync, BusinessClock businessClock,
                                                       ObjectProvider<Clock> realTime,
                                                       @Value("${seed.savings.reconcile-gap:PT1H}") Duration readGap) {
        return new ReconcileGoalsUseCase(syncLog, sync, businessClock, realTime.getIfAvailable(Clock::systemUTC), readGap);
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
