package com.paypalseed.savings.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.application.ports.CreditedPayments.Source;
import com.paypalseed.savings.application.usecases.CreditPaymentUseCase;
import com.paypalseed.savings.application.usecases.GoalNotVisibleYetException;
import com.paypalseed.savings.application.usecases.GoalVisibility;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.application.usecases.SavingsFlowSettings;
import com.paypalseed.savings.application.usecases.StartGoalUseCase;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase.State;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsWallet;
import com.paypalseed.savings.infrastructure.persistence.SavingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A report that beats the commit of its goal is delivered again; one for a goal that never appears is dropped once it is
 * older than the window (ADR 0027).
 */
class GoalVisibilityTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(1);

    private final SavingsRepository goals = new SavingsRepository();
    private final Doubles.Payments payments = new Doubles.Payments();
    private final Doubles.Outbox outbox = new Doubles.Outbox();
    private final Doubles.Ledger ledger = new Doubles.Ledger();
    private final Doubles.Credited credited = new Doubles.Credited();
    private final FakeBusinessClock business = new FakeBusinessClock(LocalDate.of(2026, 11, 10));
    private final GoalVisibility visibility = new GoalVisibility(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW);
    private final CreditPaymentUseCase credit = new CreditPaymentUseCase(credited,
        new RecordDepositUseCase(goals, outbox, ledger, business), goals, visibility);
    private final SubscriptionStateUseCase states = new SubscriptionStateUseCase(goals, payments, visibility);
    private final StartGoalUseCase start = new StartGoalUseCase(goals, payments, outbox, business,
        new SavingsFlowSettings("https://app.test/return", "https://app.test/cancel", Duration.ofHours(24)));

    private SavingsGoal activeGoal() {
        SavingsGoal goal = start.execute(new StartGoalUseCase.Command(UUID.randomUUID().toString(), "Mixer", Money.usd(500),
            Money.usd(25), null, SavingsWallet.PAYPAL, "k")).goal();
        states.apply(goal.getId(), goal.getPayPalSubscriptionId(), State.ACTIVE, NOW.minusSeconds(5));
        return goals.findById(goal.getId()).orElseThrow();
    }

    @Test
    void aYoungPaymentForAMissingGoalFailsSoThatItIsDeliveredAgainAndClaimsNothing() {
        UUID goalId = UUID.randomUUID();

        assertThatThrownBy(() -> credit.creditEvent(goalId, "SALE-1", 2_500, Source.SUBSCRIPTION, NOW.minusSeconds(30)))
            .isInstanceOfSatisfying(GoalNotVisibleYetException.class, e -> assertThat(e.goalId()).isEqualTo(goalId));

        assertThat(credited.claimed).isEmpty();
        assertThat(ledger.postings).isEmpty();
    }

    @Test
    void theSameEventIsCreditedOnceTheGoalIsThere() {
        SavingsGoal goal = activeGoal();

        assertThat(credit.creditEvent(goal.getId(), "SALE-1", 2_500, Source.SUBSCRIPTION, NOW.minusSeconds(30))).isTrue();
        assertThat(credit.creditEvent(goal.getId(), "SALE-1", 2_500, Source.SUBSCRIPTION, NOW.minusSeconds(30)))
            .as("delivered again: nothing more").isFalse();

        assertThat(ledger.postings).hasSize(1);
    }

    @Test
    void anOldPaymentForAMissingGoalIsDroppedNotRetried() {
        assertThat(credit.creditEvent(UUID.randomUUID(), "SALE-2", 2_500, Source.SUBSCRIPTION, NOW.minus(WINDOW).minusSeconds(1)))
            .isFalse();
        assertThat(credit.creditEvent(UUID.randomUUID(), "CAP-2", 800, Source.TOPUP, NOW.minus(Duration.ofDays(3)))).isFalse();

        assertThat(credited.claimed).isEmpty();
        assertThat(ledger.postings).isEmpty();
    }

    @Test
    void theWindowIsTheLimitOfTheRetries() {
        UUID goalId = UUID.randomUUID();

        assertThatThrownBy(() -> credit.creditEvent(goalId, "SALE-3", 100, Source.SUBSCRIPTION, NOW.minus(WINDOW).plusSeconds(1)))
            .isInstanceOf(GoalNotVisibleYetException.class);
        assertThat(credit.creditEvent(goalId, "SALE-3", 100, Source.SUBSCRIPTION, NOW.minus(WINDOW))).isFalse();
    }

    @Test
    void aPaymentWithoutADateOrWithoutAWindowIsNeverWaitedFor() {
        UUID goalId = UUID.randomUUID();
        CreditPaymentUseCase noWindow = new CreditPaymentUseCase(credited, new RecordDepositUseCase(goals, outbox, ledger, business),
            goals);

        assertThat(credit.creditEvent(goalId, "SALE-4", 100, Source.SUBSCRIPTION, null)).isFalse();
        assertThat(noWindow.creditEvent(goalId, "SALE-5", 100, Source.SUBSCRIPTION, NOW)).isFalse();
    }

    @Test
    void aYoungSubscriptionReportForAMissingGoalFailsAndAnOldOneIsDropped() {
        UUID goalId = UUID.randomUUID();

        assertThatThrownBy(() -> states.apply(goalId, "I-1", State.ACTIVE, NOW.minusSeconds(10)))
            .isInstanceOf(GoalNotVisibleYetException.class);
        states.apply(goalId, "I-1", State.ACTIVE, NOW.minus(WINDOW).minusSeconds(1));
    }

    @Test
    void aSubscriptionReportForAGoalThatExistsIsAppliedAsBefore() {
        SavingsGoal goal = start.execute(new StartGoalUseCase.Command(UUID.randomUUID().toString(), "Oven", Money.usd(500),
            Money.usd(25), null, SavingsWallet.PAYPAL, "k2")).goal();

        states.apply(goal.getId(), goal.getPayPalSubscriptionId(), State.ACTIVE, NOW.minusSeconds(1));

        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void aNegativeWindowIsRefused() {
        assertThatThrownBy(() -> new GoalVisibility(Clock.systemUTC(), Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
