package com.paypalseed.savings.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.events.GoalCreatedEvent;
import com.paypalseed.savings.application.ports.CreditedPayments.Source;
import com.paypalseed.savings.application.usecases.CreditPaymentUseCase;
import com.paypalseed.savings.application.usecases.ExpireApprovalsUseCase;
import com.paypalseed.savings.application.usecases.ManageGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.application.usecases.SavingsFlowSettings;
import com.paypalseed.savings.application.usecases.StartGoalUseCase;
import com.paypalseed.savings.application.usecases.StartGoalUseCase.Started;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase.State;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsWallet;
import com.paypalseed.savings.infrastructure.persistence.SavingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The goal lifecycle against a PayPal that only records what it is asked. */
class GoalFlowUseCasesTest {

    private final SavingsRepository goals = new SavingsRepository();
    private final Doubles.Payments payments = new Doubles.Payments();
    private final Doubles.Outbox outbox = new Doubles.Outbox();
    private final Doubles.Ledger ledger = new Doubles.Ledger();
    private final Doubles.Credited credited = new Doubles.Credited();
    private final FakeBusinessClock clock = new FakeBusinessClock(LocalDate.of(2026, 11, 10));
    private final SavingsFlowSettings settings = new SavingsFlowSettings("https://app.test/return", "https://app.test/cancel",
        Duration.ofHours(24));
    private final StartGoalUseCase start = new StartGoalUseCase(goals, payments, outbox, clock, settings);
    private final ManageGoalUseCase manage = new ManageGoalUseCase(goals, payments, clock, settings);
    private final SubscriptionStateUseCase states = new SubscriptionStateUseCase(goals, payments);
    private final CreditPaymentUseCase credit = new CreditPaymentUseCase(credited,
        new RecordDepositUseCase(goals, outbox, ledger, clock), goals);
    private final ExpireApprovalsUseCase expiry = new ExpireApprovalsUseCase(goals, clock);

    private final String member = UUID.randomUUID().toString();

    private Started create(String key) {
        return start.execute(new StartGoalUseCase.Command(member, "Mixer", Money.usd(500), Money.usd(25), null, SavingsWallet.PAYPAL, key));
    }

    private SavingsGoal activate(SavingsGoal goal) {
        states.apply(goal.getId(), goal.getPayPalSubscriptionId(), State.ACTIVE, clock.now());
        return goals.findById(goal.getId()).orElseThrow();
    }

    @Test
    void creatingAGoalAsksPayPalForASubscriptionAndWaitsForApproval() {
        Started started = create("key-1");
        SavingsGoal goal = started.goal();

        assertThat(started.replayed()).isFalse();
        assertThat(goal.getStatus()).isEqualTo(GoalStatus.PENDING_APPROVAL);
        assertThat(goal.getPayPalSubscriptionId()).isEqualTo("I-1");
        assertThat(goal.getApprovalUrl()).startsWith("https://paypal.test/approve/I-1");
        assertThat(goal.getApprovalExpiresAt()).isEqualTo(clock.now().plus(Duration.ofHours(24)));
        assertThat(payments.calls).containsExactly("start:" + goal.getId() + ":2500:key-1");
        assertThat(outbox.of(GoalCreatedEvent.class)).hasSize(1);
    }

    @Test
    void theReturnAndCancelUrlsNameTheGoal() {
        assertThat(settings.returnUrlFor(UUID.fromString("00000000-0000-0000-0000-000000000001")))
            .isEqualTo("https://app.test/return?goalId=00000000-0000-0000-0000-000000000001");
        assertThat(new SavingsFlowSettings("https://a.test/r?x=1", "https://a.test/c", Duration.ofHours(1))
            .returnUrlFor(UUID.fromString("00000000-0000-0000-0000-000000000001")))
            .isEqualTo("https://a.test/r?x=1&goalId=00000000-0000-0000-0000-000000000001");
    }

    @Test
    void theSameKeyReturnsTheSameGoalAndAsksPayPalOnce() {
        Started first = create("key-1");
        Started again = create("key-1");

        assertThat(again.replayed()).isTrue();
        assertThat(again.goal().getId()).isEqualTo(first.goal().getId());
        assertThat(payments.subscriptionsStarted).isEqualTo(1);
        assertThat(outbox.of(GoalCreatedEvent.class)).hasSize(1);
    }

    @Test
    void theSameKeyForADifferentGoalIsAConflict() {
        create("key-1");

        assertThatThrownBy(() -> start.execute(new StartGoalUseCase.Command(member, "Mixer", Money.usd(500), Money.usd(30), null,
            SavingsWallet.PAYPAL, "key-1")))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void aKeyIsRequiredAndPayPalNeedsWholeDollars() {
        assertThatThrownBy(() -> create(" "))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(SavingsErrors.IDEMPOTENCY_KEY_REQUIRED));
        assertThatThrownBy(() -> start.execute(new StartGoalUseCase.Command(member, "Mixer", Money.usd(500), Money.usd(25.50), null,
            SavingsWallet.PAYPAL, "k")))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(SavingsErrors.AMOUNT_NOT_WHOLE_DOLLARS));
        assertThat(payments.calls).isEmpty();
    }

    @Test
    void aVenmoGoalHasNoSubscriptionAndIsActiveAtOnce() {
        SavingsGoal goal = start.execute(new StartGoalUseCase.Command(member, "Mixer", Money.usd(500), Money.usd(25.50), null,
            SavingsWallet.VENMO, "k")).goal();

        assertThat(goal.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(goal.getPayPalSubscriptionId()).isNull();
        assertThat(payments.calls).isEmpty();
    }

    @Test
    void goalsWaitingForApprovalCountAgainstTheLimitOfThree() {
        create("a");
        create("b");
        create("c");

        assertThatThrownBy(() -> create("d")).isInstanceOf(IllegalStateException.class).hasMessageContaining("Maximum limit");
    }

    @Test
    void approvalActivatesTheGoalAndADepositIsThenCreditedOnce() {
        SavingsGoal goal = create("k").goal();

        assertThatThrownBy(() -> credit.credit(goal.getId(), "SALE-1", 2_500, Source.SUBSCRIPTION))
            .isInstanceOf(IllegalStateException.class);

        activate(goal);
        assertThat(credit.credit(goal.getId(), "SALE-2", 2_500, Source.SUBSCRIPTION)).isTrue();
        assertThat(credit.credit(goal.getId(), "SALE-2", 2_500, Source.SUBSCRIPTION)).isFalse();

        assertThat(goals.findById(goal.getId()).orElseThrow().getCurrentBalance()).isEqualTo(Money.usd(25));
        assertThat(ledger.postings).hasSize(1);
    }

    @Test
    void aPaymentForAGoalSeedHasNoRecordOfIsDroppedNotRetried() {
        assertThat(credit.credit(UUID.randomUUID(), "SALE-X", 2_500, Source.SUBSCRIPTION)).isFalse();
        assertThat(credit.credit(UUID.randomUUID(), "CAP-X", 800, Source.TOPUP)).isFalse();

        assertThat(ledger.postings).isEmpty();
        assertThat(credited.claimed).as("nothing was claimed").isEmpty();
    }

    @Test
    void stateReportsMatchOnlyTheCurrentSubscriptionAndAreSafeToRepeat() {
        SavingsGoal goal = create("k").goal();
        activate(goal);

        states.apply(goal.getId(), "I-OLD", State.CANCELLED, clock.now());
        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.ACTIVE);

        states.apply(goal.getId(), "I-1", State.CANCELLED, clock.now());
        states.apply(goal.getId(), "I-1", State.CANCELLED, clock.now());
        states.apply(goal.getId(), "I-1", State.ACTIVE, clock.now());
        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.PAUSED);
    }

    @Test
    void anApprovalAfterTheGoalClosedCancelsTheSubscription() {
        SavingsGoal goal = create("k").goal();
        clock.set(LocalDate.of(2026, 11, 12));
        assertThat(expiry.expireDue()).isEqualTo(1);

        states.apply(goal.getId(), "I-1", State.ACTIVE, clock.now());

        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.EXPIRED);
        assertThat(payments.calls).anyMatch(c -> c.startsWith("cancel:" + goal.getId()));
    }

    @Test
    void aSuspendedReportFromBeforeTheMemberResumedDoesNotPauseTheGoalAgain() {
        Instant base = Instant.now();
        SavingsGoal goal = create("k").goal();
        states.apply(goal.getId(), "I-1", State.ACTIVE, base.minus(Duration.ofHours(2)));
        manage.pause(goal.getId());
        manage.resume(goal.getId());

        states.apply(goal.getId(), "I-1", State.SUSPENDED, base.minus(Duration.ofHours(1)));

        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.ACTIVE);

        states.apply(goal.getId(), "I-1", State.SUSPENDED, Instant.now().plus(Duration.ofHours(1)));
        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.PAUSED);
    }

    @Test
    void reportsAreOrderedByWhenTheyHappenedNotWhenTheyArrived() {
        Instant base = Instant.parse("2026-11-01T12:00:00Z");
        SavingsGoal goal = create("k").goal();
        states.apply(goal.getId(), "I-1", State.ACTIVE, base);

        states.apply(goal.getId(), "I-1", State.ACTIVE, base.plusSeconds(600));
        states.apply(goal.getId(), "I-1", State.SUSPENDED, base.plusSeconds(300)); // overtaken by the later ACTIVE
        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(goals.findById(goal.getId()).orElseThrow().getSubscriptionStateAt()).isEqualTo(base.plusSeconds(600));

        states.apply(goal.getId(), "I-1", State.SUSPENDED, base.plusSeconds(600)); // the same instant is not old
        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.PAUSED);
    }

    @Test
    void aCancelledOrExpiredSubscriptionNeverComesBackSoItIsAppliedWhateverItsTime() {
        Instant base = Instant.parse("2026-11-01T12:00:00Z");
        SavingsGoal cancelled = create("a").goal();
        states.apply(cancelled.getId(), "I-1", State.ACTIVE, base);
        states.apply(cancelled.getId(), "I-1", State.CANCELLED, base.minusSeconds(3_600));
        assertThat(goals.findById(cancelled.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.PAUSED);

        SavingsGoal expired = create("b").goal();
        states.apply(expired.getId(), "I-2", State.ACTIVE, base);
        states.apply(expired.getId(), "I-2", State.EXPIRED, base.minusSeconds(3_600));
        assertThat(goals.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.PAUSED);
        assertThat(goals.findById(expired.getId()).orElseThrow().getSubscriptionStateAt())
            .as("the latest known state never moves back").isEqualTo(base);
    }

    @Test
    void aNewSubscriptionStartsWithoutTheOldOnesHistory() {
        Instant base = Instant.parse("2026-11-01T12:00:00Z");
        SavingsGoal goal = create("k").goal();
        states.apply(goal.getId(), "I-1", State.ACTIVE, base);
        states.apply(goal.getId(), "I-1", State.CANCELLED, base.plusSeconds(60));
        payments.setStatus(goal.getId(), "CANCELLED");

        SavingsGoal renewed = manage.resume(goal.getId());

        assertThat(renewed.getPayPalSubscriptionId()).isEqualTo("I-2");
        assertThat(renewed.getSubscriptionStateAt()).as("the old subscription history is dropped").isNull();
        // The new subscription's own first report is not stale next to the old one's history.
        states.apply(goal.getId(), "I-2", State.ACTIVE, Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void expiryTakesOnlyGoalsWhoseWindowClosed() {
        SavingsGoal waiting = create("a").goal();
        SavingsGoal approved = create("b").goal();
        activate(approved);
        clock.set(LocalDate.of(2026, 11, 12));

        assertThat(expiry.expireDue()).isEqualTo(1);
        assertThat(expiry.expireDue()).isZero();

        assertThat(goals.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.EXPIRED);
        assertThat(goals.findById(approved.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void pausingSuspendsAndResumingActivatesTheSubscriptionWithADifferentRequestIdEachTime() {
        SavingsGoal goal = activate(create("k").goal());

        manage.pause(goal.getId());
        manage.resume(goal.getId());
        manage.pause(goal.getId());

        assertThat(payments.calls.stream().filter(c -> !c.startsWith("start")).toList()).hasSize(3)
            .allMatch(c -> c.startsWith("suspend:") || c.startsWith("activate:"));
        assertThat(payments.calls.stream().filter(c -> !c.startsWith("start")).distinct()).hasSize(3);
        assertThat(goals.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.PAUSED);
    }

    @Test
    void resumingAGoalWhoseSubscriptionPayPalEndedStartsANewApproval() {
        SavingsGoal goal = activate(create("k").goal());
        states.apply(goal.getId(), "I-1", State.CANCELLED, clock.now());
        payments.setStatus(goal.getId(), "CANCELLED");

        SavingsGoal resumed = manage.resume(goal.getId());

        assertThat(resumed.getStatus()).isEqualTo(GoalStatus.PENDING_APPROVAL);
        assertThat(resumed.getPayPalSubscriptionId()).isEqualTo("I-2");
        assertThat(resumed.getApprovalUrl()).contains("I-2");
        assertThat(payments.calls).noneMatch(c -> c.startsWith("activate:"));
    }

    @Test
    void changingTheAmountRevisesTheSubscriptionAndRefusesCentsAndPendingGoals() {
        SavingsGoal pending = create("k").goal();
        assertThatThrownBy(() -> manage.changeMonthlyAmount(pending.getId(), Money.usd(40))).isInstanceOf(IllegalStateException.class);
        activate(pending);

        SavingsGoal changed = manage.changeMonthlyAmount(pending.getId(), Money.usd(40));

        assertThat(changed.getMonthlyAmount()).isEqualTo(Money.usd(40));
        assertThat(payments.calls).anyMatch(c -> c.startsWith("revise:" + pending.getId() + ":4000:"));
        assertThatThrownBy(() -> manage.changeMonthlyAmount(pending.getId(), Money.usd(40.50)))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(SavingsErrors.AMOUNT_NOT_WHOLE_DOLLARS));
    }

    @Test
    void aGoalWithoutASubscriptionAtPayPalChangesLocallyOnly() {
        SavingsGoal legacy = goals.save(SavingsGoal.create(member, "Legacy", Money.usd(100), Money.usd(10), null, "sub-by-client"));

        manage.pause(legacy.getId());
        manage.resume(legacy.getId());

        assertThat(payments.calls).isEmpty();
        assertThat(goals.findById(legacy.getId()).orElseThrow().getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }
}
