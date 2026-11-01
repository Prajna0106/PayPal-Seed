package com.paypalseed.savings.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.events.WithdrawalFailedEvent;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import com.paypalseed.savings.api.events.WithdrawalReturnedEvent;
import com.paypalseed.savings.application.ports.SavingsPayments.PayoutState;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.application.usecases.WithdrawUseCase;
import com.paypalseed.savings.application.usecases.WithdrawalOutcomeUseCase;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsWallet;
import com.paypalseed.savings.domain.model.Withdrawal;
import com.paypalseed.savings.domain.model.WithdrawalStatus;
import com.paypalseed.savings.infrastructure.persistence.SavingsRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reserve, payout and the three ways a payout can end, with the books checked after every step. */
class WithdrawalSagaTest {

    private final SavingsRepository goals = new SavingsRepository();
    private final Doubles.Withdrawals withdrawals = new Doubles.Withdrawals();
    private final Doubles.Payments payments = new Doubles.Payments();
    private final Doubles.Recipients recipients = new Doubles.Recipients();
    private final Doubles.Outbox outbox = new Doubles.Outbox();
    private final Doubles.Ledger ledger = new Doubles.Ledger();
    private final FakeBusinessClock clock = new FakeBusinessClock(LocalDate.of(2026, 11, 10));
    private final WithdrawalOutcomeUseCase outcomes = new WithdrawalOutcomeUseCase(goals, withdrawals, outbox, ledger, clock);
    private final WithdrawUseCase withdraw = new WithdrawUseCase(goals, withdrawals, payments, recipients, outcomes, clock);

    private final UUID member = UUID.randomUUID();
    private SavingsGoal goal;

    @BeforeEach
    void aGoalWithOneHundredDollars() {
        goal = goals.save(SavingsGoal.create(member.toString(), "Mixer", Money.usd(500), Money.usd(25), null, "sub"));
        new RecordDepositUseCase(goals, outbox, ledger, clock).execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(100), "cap-1"));
    }

    private long savingsInTheBooks() {
        return ledger.balanceCents(AccountRef.of(AccountType.MEMBER_SAVINGS, member));
    }

    private SavingsGoal reload() {
        return goals.findById(goal.getId()).orElseThrow();
    }

    private Withdrawal ask(String key, double dollars) {
        return withdraw.execute(new WithdrawUseCase.Command(goal.getId(), Money.usd(dollars), null, key));
    }

    private void assertBooksBalance() {
        assertThat(ledger.debitsMinusCredits()).isZero();
    }

    @Test
    void asking_reservesTheMoneyAndSendsThePayoutWithTheWithdrawalIdAsReference() {
        Withdrawal w = ask("k", 30);

        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(payments.calls).containsExactly("payout:" + w.getId() + ":3000:PAYPAL");
        assertThat(reload().getCurrentBalance()).isEqualTo(Money.usd(70));
        assertThat(reload().getReservedBalance()).isEqualTo(Money.usd(30));
        assertThat(savingsInTheBooks()).as("nothing is booked until the payout succeeds").isEqualTo(10_000);
        assertBooksBalance();
    }

    @Test
    void successBooksTheMoneyOnceWhateverNumberOfTimesPayPalSaysIt() {
        Withdrawal w = ask("k", 30);

        assertThat(outcomes.succeeded(w.getId(), "TXN-9")).isTrue();
        assertThat(outcomes.succeeded(w.getId(), "TXN-9")).isFalse();

        assertThat(withdrawals.byId.get(w.getId()).getStatus()).isEqualTo(WithdrawalStatus.SUCCEEDED);
        assertThat(reload().getReservedBalance()).isEqualTo(Money.zeroUsd());
        assertThat(reload().getCurrentBalance()).isEqualTo(Money.usd(70));
        assertThat(savingsInTheBooks()).isEqualTo(7_000);
        assertThat(ledger.balanceCents(AccountRef.program(AccountType.PROGRAM_CASH))).isEqualTo(7_000);
        assertThat(outbox.of(WithdrawalProcessedEvent.class)).hasSize(1);
        assertBooksBalance();
    }

    @Test
    void anInstantSuccessCompletesInTheSameCall() {
        payments.nextPayout = PayoutState.SUCCEEDED;

        Withdrawal w = ask("k", 30);

        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.SUCCEEDED);
        assertThat(w.getPaypalTransactionId()).isEqualTo("TXN-" + w.getId());
        assertThat(savingsInTheBooks()).isEqualTo(7_000);
    }

    @Test
    void aTerminalFailureGivesTheReserveBackAndTellsTheMember() {
        Withdrawal w = ask("k", 30);

        assertThat(outcomes.failed(w.getId(), "RECEIVER_UNREGISTERED", true)).isTrue();
        assertThat(outcomes.failed(w.getId(), "RECEIVER_UNREGISTERED", true)).isFalse();

        assertThat(withdrawals.byId.get(w.getId()).getStatus()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(reload().getCurrentBalance()).isEqualTo(Money.usd(100));
        assertThat(reload().getReservedBalance()).isEqualTo(Money.zeroUsd());
        assertThat(savingsInTheBooks()).isEqualTo(10_000);
        assertThat(outbox.of(WithdrawalFailedEvent.class)).singleElement()
            .satisfies(e -> assertThat(e.reasonCode()).isEqualTo("RECEIVER_UNREGISTERED"));
        assertBooksBalance();
    }

    @Test
    void aFailureReportedByPayPalInTheSameCallIsHandledInTheSameCall() {
        payments.nextPayout = PayoutState.FAILED;
        payments.nextReason = "RECEIVER_BLOCKED";

        Withdrawal w = ask("k", 30);

        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(w.getFailureReason()).isEqualTo("RECEIVER_BLOCKED");
        assertThat(reload().getCurrentBalance()).isEqualTo(Money.usd(100));
    }

    @Test
    void unclaimedMoneyKeepsTheReserveUntilPayPalSettlesIt() {
        Withdrawal w = ask("k", 30);

        assertThat(outcomes.failed(w.getId(), "UNCLAIMED", false)).isFalse();
        assertThat(reload().getReservedBalance()).isEqualTo(Money.usd(30));

        assertThat(outcomes.returned(w.getId(), "UNCLAIMED_EXPIRED")).isTrue();
        assertThat(withdrawals.byId.get(w.getId()).getStatus()).isEqualTo(WithdrawalStatus.RETURNED);
        assertThat(reload().getCurrentBalance()).isEqualTo(Money.usd(100));
        assertThat(savingsInTheBooks()).as("never booked, so nothing to reverse").isEqualTo(10_000);
        assertThat(ledger.postings.keySet()).noneMatch(k -> k.startsWith("savings-withdrawal"));
    }

    @Test
    void aPayoutTakenBackAfterItArrivedReversesTheBookingAndRestoresTheGoal() {
        Withdrawal w = ask("k", 30);
        outcomes.succeeded(w.getId(), "TXN-9");

        assertThat(outcomes.returned(w.getId(), "RETURNED")).isTrue();
        assertThat(outcomes.returned(w.getId(), "RETURNED")).isFalse();

        assertThat(reload().getCurrentBalance()).isEqualTo(Money.usd(100));
        assertThat(savingsInTheBooks()).isEqualTo(10_000);
        assertThat(ledger.balanceCents(AccountRef.program(AccountType.PROGRAM_CASH))).isEqualTo(10_000);
        assertThat(outbox.of(WithdrawalReturnedEvent.class)).hasSize(1);
        assertBooksBalance();
    }

    @Test
    void theSameKeyGivesTheSameWithdrawalAndOnePayout() {
        Withdrawal first = ask("k", 30);
        Withdrawal again = ask("k", 30);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(payments.calls).hasSize(1);
        assertThat(reload().getReservedBalance()).isEqualTo(Money.usd(30));
    }

    @Test
    void theSameKeyForAnotherAmountIsAConflict() {
        ask("k", 30);

        assertThatThrownBy(() -> ask("k", 31)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void theSameMoneyCannotBeSpentTwice() {
        ask("one", 70);

        assertThatThrownBy(() -> ask("two", 40)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Insufficient");

        assertThat(withdrawals.byId).hasSize(1);
        assertThat(payments.calls).hasSize(1);
        assertThat(reload().getReservedBalance()).isEqualTo(Money.usd(70));
    }

    @Test
    void aKeyIsRequired() {
        assertThatThrownBy(() -> ask(null, 10)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(SavingsErrors.IDEMPOTENCY_KEY_REQUIRED));
    }

    @Test
    void withoutAPayPalAccountOnFileNothingIsReserved() {
        recipients.recipient = null;

        assertThatThrownBy(() -> ask("k", 10)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(SavingsErrors.PAYOUT_RECIPIENT_MISSING));
        assertThat(reload().getReservedBalance()).isEqualTo(Money.zeroUsd());
        assertThat(payments.calls).isEmpty();
    }

    @Test
    void theWalletIsTheMembersChoiceThenTheirPreferenceThenTheGoals() {
        recipients.recipient = new com.paypalseed.savings.application.ports.PayoutRecipients.Recipient("PAYER-A", "a@x.test", SavingsWallet.VENMO);

        Withdrawal preferred = ask("a", 5);
        Withdrawal chosen = withdraw.execute(new WithdrawUseCase.Command(goal.getId(), Money.usd(5), SavingsWallet.PAYPAL, "b"));

        assertThat(preferred.getWallet()).isEqualTo(SavingsWallet.VENMO);
        assertThat(chosen.getWallet()).isEqualTo(SavingsWallet.PAYPAL);
    }

    @Test
    void aReferenceThatIsNotAWithdrawalChangesNothing() {
        assertThat(outcomes.succeeded(UUID.randomUUID(), "TXN")).isFalse();
        assertThat(outcomes.failed(UUID.randomUUID(), "X", true)).isFalse();
        assertThat(outcomes.returned(UUID.randomUUID(), "X")).isFalse();
    }
}
