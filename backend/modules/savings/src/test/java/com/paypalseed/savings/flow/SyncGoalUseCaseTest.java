package com.paypalseed.savings.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.application.ports.GoalSyncLog;
import com.paypalseed.savings.application.ports.SavingsPayments.SaleReport;
import com.paypalseed.savings.application.ports.SavingsPayments.StateReport;
import com.paypalseed.savings.application.ports.SavingsPayments.SubscriptionReport;
import com.paypalseed.savings.application.ports.SavingsPayments.SubscriptionState;
import com.paypalseed.savings.application.usecases.CreditPaymentUseCase;
import com.paypalseed.savings.application.usecases.ReconcileGoalsUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.application.usecases.SavingsFlowSettings;
import com.paypalseed.savings.application.usecases.StartGoalUseCase;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase;
import com.paypalseed.savings.application.usecases.SyncGoalUseCase;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsWallet;
import com.paypalseed.savings.infrastructure.persistence.SavingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The pull path (ADR 0028): what PayPal holds goes through the same steps as a webhook, and a read is rate limited. */
class SyncGoalUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    /** The real claim is one SQL statement; this keeps the same contract in memory. */
    private static final class SyncLog implements GoalSyncLog {
        final Map<UUID, Instant> last = new HashMap<>();
        final List<UUID> due = new ArrayList<>();

        @Override
        public boolean claim(UUID goalId, Instant now, Duration minGap) {
            Instant before = last.get(goalId);
            if (before != null && before.isAfter(now.minus(minGap))) {
                return false;
            }
            last.put(goalId, now);
            return true;
        }

        @Override
        public List<UUID> due(Instant approvalNow, Instant readBefore, int limit) {
            return due.stream().limit(limit).toList();
        }
    }

    private final SavingsRepository goals = new SavingsRepository();
    private final Doubles.Payments payments = new Doubles.Payments();
    private final Doubles.Outbox outbox = new Doubles.Outbox();
    private final Doubles.Ledger ledger = new Doubles.Ledger();
    private final Doubles.Credited credited = new Doubles.Credited();
    private final SyncLog syncLog = new SyncLog();
    private final FakeBusinessClock clock = new FakeBusinessClock(LocalDate.of(2026, 11, 10));
    private final SavingsFlowSettings settings = new SavingsFlowSettings("https://app.test/return", "https://app.test/cancel",
        Duration.ofHours(24));
    private final StartGoalUseCase start = new StartGoalUseCase(goals, payments, outbox, clock, settings);
    private final SubscriptionStateUseCase states = new SubscriptionStateUseCase(goals, payments);
    private final CreditPaymentUseCase credit = new CreditPaymentUseCase(credited,
        new RecordDepositUseCase(goals, outbox, ledger, clock), goals);
    private final SyncGoalUseCase sync = new SyncGoalUseCase(goals, payments, syncLog, states, credit,
        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5));

    private SavingsGoal pendingGoal() {
        return start.execute(new StartGoalUseCase.Command(UUID.randomUUID().toString(), "Mixer", Money.usd(500), Money.usd(25), null,
            SavingsWallet.PAYPAL, "key-" + UUID.randomUUID())).goal();
    }

    private void paypalSays(SavingsGoal goal, SubscriptionState state, Instant at, SaleReport... sales) {
        payments.report = new SubscriptionReport(goal.getPayPalSubscriptionId(),
            Optional.ofNullable(state).map(s -> new StateReport(s, at)), List.of(sales));
    }

    private SavingsGoal reload(SavingsGoal goal) {
        return goals.findById(goal.getId()).orElseThrow();
    }

    @Test
    void anApprovalAndItsFirstSaleActivateTheGoalAndCreditItOnce() {
        SavingsGoal goal = pendingGoal();
        paypalSays(goal, SubscriptionState.ACTIVE, NOW.minusSeconds(60), new SaleReport("SALE-1", 2_500, NOW.minusSeconds(30)));

        SavingsGoal after = sync.execute(goal.getId()).goal();

        assertThat(after.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(after.getCurrentBalance().cents()).isEqualTo(2_500);
        assertThat(credited.claimed).containsExactly("SALE-1");
    }

    @Test
    void aSecondReadAfterTheGapChangesNothing() {
        SavingsGoal goal = pendingGoal();
        paypalSays(goal, SubscriptionState.ACTIVE, NOW.minusSeconds(60), new SaleReport("SALE-1", 2_500, NOW.minusSeconds(30)));
        sync.execute(goal.getId());
        syncLog.last.clear();

        SyncGoalUseCase.Result again = sync.execute(goal.getId());

        assertThat(again.read()).isTrue();
        assertThat(again.goal().getCurrentBalance().cents()).isEqualTo(2_500);
        assertThat(again.goal().getDepositCount()).isEqualTo(1);
    }

    @Test
    void aSaleAWebhookAlreadyCreditedIsNotCreditedAgain() {
        SavingsGoal goal = pendingGoal();
        states.apply(goal.getId(), goal.getPayPalSubscriptionId(), SubscriptionStateUseCase.State.ACTIVE, NOW.minusSeconds(120));
        credit.credit(goal.getId(), "SALE-1", 2_500, com.paypalseed.savings.application.ports.CreditedPayments.Source.SUBSCRIPTION);
        paypalSays(goal, SubscriptionState.ACTIVE, NOW.minusSeconds(120), new SaleReport("SALE-1", 2_500, NOW.minusSeconds(30)));

        SavingsGoal after = sync.execute(goal.getId()).goal();

        assertThat(after.getCurrentBalance().cents()).isEqualTo(2_500);
        assertThat(after.getDepositCount()).isEqualTo(1);
    }

    @Test
    void aStateOlderThanOneTheGoalKnowsIsIgnored() {
        SavingsGoal goal = pendingGoal();
        states.apply(goal.getId(), goal.getPayPalSubscriptionId(), SubscriptionStateUseCase.State.ACTIVE, NOW);
        paypalSays(goal, SubscriptionState.SUSPENDED, NOW.minusSeconds(600));

        assertThat(sync.execute(goal.getId()).goal().getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void aCancelledSubscriptionPausesTheGoal() {
        SavingsGoal goal = pendingGoal();
        states.apply(goal.getId(), goal.getPayPalSubscriptionId(), SubscriptionStateUseCase.State.ACTIVE, NOW.minusSeconds(600));
        paypalSays(goal, SubscriptionState.CANCELLED, NOW.minusSeconds(10));

        assertThat(sync.execute(goal.getId()).goal().getStatus()).isEqualTo(GoalStatus.PAUSED);
    }

    @Test
    void aSubscriptionNotApprovedYetLeavesTheGoalWaitingAndCreditsNothing() {
        SavingsGoal goal = pendingGoal();
        paypalSays(goal, null, null, new SaleReport("SALE-1", 2_500, NOW));

        SavingsGoal after = sync.execute(goal.getId()).goal();

        assertThat(after.getStatus()).isEqualTo(GoalStatus.PENDING_APPROVAL);
        assertThat(after.getCurrentBalance().cents()).isZero();
        assertThat(after.getDepositCount()).as("a sale a goal cannot take is left for the next read").isZero();
    }

    @Test
    void aReadInsideTheMinimumGapReturnsTheStoredGoalWithoutAskingPayPal() {
        SavingsGoal goal = pendingGoal();
        paypalSays(goal, SubscriptionState.ACTIVE, NOW, new SaleReport("SALE-1", 2_500, NOW));
        sync.execute(goal.getId());

        SyncGoalUseCase.Result second = sync.execute(goal.getId());

        assertThat(second.read()).isFalse();
        assertThat(payments.reads).isEqualTo(1);
    }

    @Test
    void aGoalWithNoLiveSubscriptionIsNotRead() {
        SavingsGoal venmo = start.execute(new StartGoalUseCase.Command(UUID.randomUUID().toString(), "Mixer", Money.usd(500),
            Money.usd(25), null, SavingsWallet.VENMO, "k")).goal();

        assertThat(sync.execute(venmo.getId()).read()).isFalse();
        assertThat(payments.reads).isZero();
    }

    @Test
    void anUnknownGoalIsNotFound() {
        assertThatThrownBy(() -> sync.execute(UUID.randomUUID())).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(SavingsErrors.GOAL_NOT_FOUND));
    }

    @Test
    void aPayPalFailureIsThrownAndTheGoalIsUnchanged() {
        SavingsGoal goal = pendingGoal();
        payments.failRead = new IllegalArgumentException("PayPal down");

        assertThatThrownBy(() -> sync.execute(goal.getId())).hasMessage("PayPal down");
        assertThat(reload(goal).getStatus()).isEqualTo(GoalStatus.PENDING_APPROVAL);
    }

    @Test
    void theReconcileSkipsAFailingGoalAndGoesOnWithTheRest() {
        SavingsGoal bad = pendingGoal();
        SavingsGoal good = pendingGoal();
        syncLog.due.addAll(List.of(bad.getId(), good.getId()));
        payments.failRead = new IllegalArgumentException("PayPal down for one");
        paypalSays(good, SubscriptionState.ACTIVE, NOW, new SaleReport("SALE-G", 2_500, NOW));
        ReconcileGoalsUseCase reconcile = new ReconcileGoalsUseCase(syncLog, sync, clock, Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofHours(1));

        ReconcileGoalsUseCase.Result result = reconcile.run(10);

        assertThat(result).isEqualTo(new ReconcileGoalsUseCase.Result(2, 1, 1));
        assertThat(reload(bad).getStatus()).isEqualTo(GoalStatus.PENDING_APPROVAL);
        assertThat(reload(good).getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(reload(good).getCurrentBalance().cents()).isEqualTo(2_500);
    }

    @Test
    void theReconcileHonoursTheBatchLimit() {
        SavingsGoal a = pendingGoal();
        SavingsGoal b = pendingGoal();
        syncLog.due.addAll(List.of(a.getId(), b.getId()));
        paypalSays(a, null, null);
        ReconcileGoalsUseCase reconcile = new ReconcileGoalsUseCase(syncLog, sync, clock, Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofHours(1));

        assertThat(reconcile.run(1).examined()).isEqualTo(1);
    }
}
