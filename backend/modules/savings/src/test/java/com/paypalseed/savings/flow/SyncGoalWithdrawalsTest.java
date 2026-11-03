package com.paypalseed.savings.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.application.ports.GoalSyncLog;
import com.paypalseed.savings.application.ports.SavingsPayments.PayoutEnd;
import com.paypalseed.savings.application.ports.SavingsPayments.PayoutReport;
import com.paypalseed.savings.application.usecases.CreditPaymentUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase;
import com.paypalseed.savings.application.usecases.SyncGoalUseCase;
import com.paypalseed.savings.application.usecases.WithdrawUseCase;
import com.paypalseed.savings.application.usecases.WithdrawalOutcomeUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.Withdrawal;
import com.paypalseed.savings.domain.model.WithdrawalStatus;
import com.paypalseed.savings.infrastructure.persistence.SavingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A refresh of a goal also reads its withdrawal payouts that PayPal has not reported, through the steps the payout events use (ADR 0029). */
class SyncGoalWithdrawalsTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final SavingsRepository goals = new SavingsRepository();
    private final Doubles.Withdrawals withdrawals = new Doubles.Withdrawals();
    private final Doubles.Payments payments = new Doubles.Payments();
    private final Doubles.Recipients recipients = new Doubles.Recipients();
    private final Doubles.Outbox outbox = new Doubles.Outbox();
    private final Doubles.Ledger ledger = new Doubles.Ledger();
    private final Doubles.Credited credited = new Doubles.Credited();
    private final FakeBusinessClock clock = new FakeBusinessClock(LocalDate.of(2026, 11, 10));
    private final WithdrawalOutcomeUseCase outcomes = new WithdrawalOutcomeUseCase(goals, withdrawals, outbox, ledger, clock);
    private final WithdrawUseCase withdraw = new WithdrawUseCase(goals, withdrawals, payments, recipients, outcomes, clock);
    private final GoalSyncLog noSubscriptionReads = new GoalSyncLog() {
        @Override
        public boolean claim(UUID goalId, Instant now, Duration minGap) {
            return false;
        }

        @Override
        public List<UUID> due(Instant approvalNow, Instant readBefore, int limit) {
            return List.of();
        }
    };
    private final SyncGoalUseCase sync = new SyncGoalUseCase(goals, payments, noSubscriptionReads,
        new SubscriptionStateUseCase(goals, payments),
        new CreditPaymentUseCase(credited, new RecordDepositUseCase(goals, outbox, ledger, clock), goals),
        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5), withdrawals, outcomes);

    private final UUID member = UUID.randomUUID();
    private SavingsGoal goal;

    @BeforeEach
    void aGoalWithOneHundredDollars() {
        goal = goals.save(SavingsGoal.create(member.toString(), "Mixer", Money.usd(500), Money.usd(25), null, "sub"));
        new RecordDepositUseCase(goals, outbox, ledger, clock).execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(100), "cap-1"));
    }

    private Withdrawal ask(String key, double dollars) {
        return withdraw.execute(new WithdrawUseCase.Command(goal.getId(), Money.usd(dollars), null, key));
    }

    private long savingsInTheBooks() {
        return ledger.balanceCents(AccountRef.of(AccountType.MEMBER_SAVINGS, member));
    }

    @Test
    void aPayoutPayPalDeliveredEndsTheWithdrawalOnceEvenWhenTheGoalHasNoLiveSubscription() {
        Withdrawal w = ask("k", 30);
        payments.payoutReports.put(w.getId().toString(), new PayoutReport(PayoutEnd.SUCCEEDED, null, "TXN-1"));

        SyncGoalUseCase.Result first = sync.execute(goal.getId());
        SyncGoalUseCase.Result second = sync.execute(goal.getId());

        assertThat(first.read()).as("the subscription was not read").isFalse();
        assertThat(withdrawals.byId.get(w.getId()).getStatus()).isEqualTo(WithdrawalStatus.SUCCEEDED);
        assertThat(first.goal().getReservedBalance()).isEqualTo(Money.zeroUsd());
        assertThat(second.goal().getCurrentBalance()).isEqualTo(Money.usd(70));
        assertThat(savingsInTheBooks()).isEqualTo(7_000);
        assertThat(payments.payoutReads).as("a delivered withdrawal is not asked about again").hasSize(1);
        assertThat(ledger.debitsMinusCredits()).isZero();
    }

    @Test
    void aFailedPayoutGivesTheReserveBackAndAnUnclaimedOneWaits() {
        Withdrawal failing = ask("k1", 30);
        Withdrawal unclaimed = ask("k2", 20);
        payments.payoutReports.put(failing.getId().toString(), new PayoutReport(PayoutEnd.FAILED, "RECEIVER_UNREGISTERED", null));
        payments.payoutReports.put(unclaimed.getId().toString(), new PayoutReport(PayoutEnd.UNCLAIMED, "UNCLAIMED", null));

        SavingsGoal after = sync.execute(goal.getId()).goal();

        assertThat(withdrawals.byId.get(failing.getId()).getStatus()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(withdrawals.byId.get(unclaimed.getId()).getStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(after.getReservedBalance()).isEqualTo(Money.usd(20));
        assertThat(after.getCurrentBalance()).isEqualTo(Money.usd(80));
        assertThat(savingsInTheBooks()).isEqualTo(10_000);
    }

    @Test
    void aPayoutStillPendingOrUnknownToPaymentsChangesNothing() {
        Withdrawal pending = ask("k1", 30);
        Withdrawal unknown = ask("k2", 10);
        payments.payoutReports.put(pending.getId().toString(), new PayoutReport(PayoutEnd.PENDING, null, null));

        SavingsGoal after = sync.execute(goal.getId()).goal();

        assertThat(withdrawals.byId.get(pending.getId()).getStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(withdrawals.byId.get(unknown.getId()).getStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(after.getReservedBalance()).isEqualTo(Money.usd(40));
    }

    @Test
    void aPayoutThatCannotBeReadNowNeverFailsTheRefreshAndTheNextOneRetries() {
        Withdrawal w = ask("k", 30);
        payments.failPayoutRead = new IllegalStateException("PayPal is unavailable");
        payments.payoutReports.put(w.getId().toString(), new PayoutReport(PayoutEnd.SUCCEEDED, null, "TXN-1"));

        SavingsGoal during = sync.execute(goal.getId()).goal();
        assertThat(withdrawals.byId.get(w.getId()).getStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(during.getReservedBalance()).isEqualTo(Money.usd(30));

        sync.execute(goal.getId());
        assertThat(withdrawals.byId.get(w.getId()).getStatus()).isEqualTo(WithdrawalStatus.SUCCEEDED);
    }

    @Test
    void aPayoutReturnedAfterNoDeliveryGivesTheReserveBack() {
        Withdrawal w = ask("k", 30);
        payments.payoutReports.put(w.getId().toString(), new PayoutReport(PayoutEnd.RETURNED, "UNCLAIMED_EXPIRED", null));

        SavingsGoal after = sync.execute(goal.getId()).goal();

        assertThat(withdrawals.byId.get(w.getId()).getStatus()).isEqualTo(WithdrawalStatus.RETURNED);
        assertThat(after.getCurrentBalance()).isEqualTo(Money.usd(100));
        assertThat(after.getReservedBalance()).isEqualTo(Money.zeroUsd());
        assertThat(savingsInTheBooks()).isEqualTo(10_000);
    }
}
