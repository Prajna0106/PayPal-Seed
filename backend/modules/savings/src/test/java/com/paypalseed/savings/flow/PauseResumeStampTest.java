package com.paypalseed.savings.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.application.usecases.ManageGoalUseCase;
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
 * A pause or a resume is stamped with real time, not with the business clock (ADR 0027): PayPal dates its own reports in
 * real time while the demo clock can run months ahead, and the two stamps are compared to order those reports.
 */
class PauseResumeStampTest {

    private static final Instant REAL_NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final SavingsRepository goals = new SavingsRepository();
    private final Doubles.Payments payments = new Doubles.Payments();
    private final Doubles.Outbox outbox = new Doubles.Outbox();
    /** The demo clock four months ahead of real time. */
    private final FakeBusinessClock business = new FakeBusinessClock(LocalDate.of(2027, 3, 10));
    private final Clock real = Clock.fixed(REAL_NOW, ZoneOffset.UTC);
    private final SavingsFlowSettings settings = new SavingsFlowSettings("https://app.test/return", "https://app.test/cancel",
        Duration.ofHours(24));
    private final StartGoalUseCase start = new StartGoalUseCase(goals, payments, outbox, business, settings);
    private final ManageGoalUseCase manage = new ManageGoalUseCase(goals, payments, business, real, settings);
    private final SubscriptionStateUseCase states = new SubscriptionStateUseCase(goals, payments);

    private SavingsGoal activeGoal() {
        SavingsGoal goal = start.execute(new StartGoalUseCase.Command(UUID.randomUUID().toString(), "Mixer", Money.usd(500),
            Money.usd(25), null, SavingsWallet.PAYPAL, "k")).goal();
        states.apply(goal.getId(), goal.getPayPalSubscriptionId(), State.ACTIVE, REAL_NOW.minus(Duration.ofHours(3)));
        return goals.findById(goal.getId()).orElseThrow();
    }

    private SavingsGoal reload(SavingsGoal goal) {
        return goals.findById(goal.getId()).orElseThrow();
    }

    @Test
    void aPauseIsStampedWithTheRealClockNotTheBusinessClock() {
        SavingsGoal goal = activeGoal();

        manage.pause(goal.getId());

        assertThat(reload(goal).getSubscriptionStateAt()).isEqualTo(REAL_NOW);
        assertThat(business.now()).isAfter(REAL_NOW.plus(Duration.ofDays(90)));
    }

    @Test
    void aResumeIsStampedWithTheRealClockToo() {
        SavingsGoal goal = activeGoal();
        manage.pause(goal.getId());
        Clock later = Clock.fixed(REAL_NOW.plusSeconds(600), ZoneOffset.UTC);

        new ManageGoalUseCase(goals, payments, business, later, settings).resume(goal.getId());

        assertThat(reload(goal).getSubscriptionStateAt()).isEqualTo(REAL_NOW.plusSeconds(600));
        assertThat(reload(goal).getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void aSuspendedReportDatedBeforeTheMembersResumeIsStaleAndOneAfterItIsNot() {
        SavingsGoal goal = activeGoal();
        manage.pause(goal.getId());
        Clock resumedAt = Clock.fixed(REAL_NOW.plusSeconds(600), ZoneOffset.UTC);
        new ManageGoalUseCase(goals, payments, business, resumedAt, settings).resume(goal.getId());

        states.apply(goal.getId(), "I-1", State.SUSPENDED, REAL_NOW.plusSeconds(300));
        assertThat(reload(goal).getStatus()).as("older than the resume: ignored").isEqualTo(GoalStatus.ACTIVE);

        states.apply(goal.getId(), "I-1", State.SUSPENDED, REAL_NOW.plusSeconds(601));
        assertThat(reload(goal).getStatus()).as("newer than the resume: applied").isEqualTo(GoalStatus.PAUSED);
    }

    @Test
    void theShortConstructorStillUsesRealTime() {
        SavingsGoal goal = start.execute(new StartGoalUseCase.Command(UUID.randomUUID().toString(), "Oven", Money.usd(500),
            Money.usd(25), null, SavingsWallet.PAYPAL, "k-real")).goal();
        states.apply(goal.getId(), goal.getPayPalSubscriptionId(), State.ACTIVE, Instant.now().minusSeconds(60));
        Instant before = Instant.now();

        new ManageGoalUseCase(goals, payments, business, settings).pause(goal.getId());

        assertThat(reload(goal).getSubscriptionStateAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }
}
