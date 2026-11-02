package com.paypalseed.savings.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsWallet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SavingsGoalPayPalTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private static SavingsGoal pending() {
        return SavingsGoal.createForSubscription(UUID.randomUUID(), "m", "Mixer", Money.usd(500), Money.usd(25), null,
            SavingsWallet.PAYPAL, "key", NOW.plusSeconds(3_600), NOW);
    }

    private static SavingsGoal activeWith(double balance) {
        SavingsGoal goal = pending();
        goal.activate(NOW);
        goal.recordDeposit(Money.usd(balance), "cap");
        return goal;
    }

    @Test
    void aPayPalGoalWaitsForApprovalAndTakesNoDepositUntilThen() {
        SavingsGoal goal = pending();

        assertThat(goal.getStatus()).isEqualTo(GoalStatus.PENDING_APPROVAL);
        assertThat(goal.acceptsDeposits()).isFalse();
        assertThatThrownBy(() -> goal.recordDeposit(Money.usd(25), "cap"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("pending");

        assertThat(goal.activate(NOW)).isTrue();
        assertThat(goal.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        goal.recordDeposit(Money.usd(25), "cap");
        assertThat(goal.getCurrentBalance()).isEqualTo(Money.usd(25));
    }

    @Test
    void aVenmoGoalIsActiveAtOnceBecauseItHasNoSubscription() {
        SavingsGoal goal = SavingsGoal.createForSubscription(UUID.randomUUID(), "m", "Mixer", Money.usd(500), Money.usd(25), null,
            SavingsWallet.VENMO, "key", null, NOW);

        assertThat(goal.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(goal.getApprovalExpiresAt()).isNull();
        assertThat(goal.acceptsDeposits()).isTrue();
    }

    @Test
    void activatingAgainOrAfterExpiryChangesNothing() {
        SavingsGoal goal = pending();
        goal.activate(NOW);
        assertThat(goal.activate(NOW)).isFalse();

        SavingsGoal late = pending();
        late.expireApproval(NOW.plusSeconds(7_200));
        assertThat(late.activate(NOW)).isFalse();
        assertThat(late.getStatus()).isEqualTo(GoalStatus.EXPIRED);
        assertThatThrownBy(() -> late.recordDeposit(Money.usd(25), "cap")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void approvalExpiresOnlyAfterTheWindowAndOnlyWhilePending() {
        SavingsGoal goal = pending();
        assertThat(goal.approvalExpired(NOW.plusSeconds(3_599))).isFalse();
        assertThat(goal.approvalExpired(NOW.plusSeconds(3_600))).isTrue();

        goal.activate(NOW);
        assertThat(goal.approvalExpired(NOW.plusSeconds(99_999))).isFalse();
        assertThatThrownBy(() -> goal.expireApproval(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSubscriptionThatEndedPausesAnActiveGoalAndNothingElse() {
        SavingsGoal goal = activeWith(25);
        assertThat(goal.pauseBecauseSubscriptionEnded(NOW)).isTrue();
        assertThat(goal.getStatus()).isEqualTo(GoalStatus.PAUSED);
        assertThat(goal.pauseBecauseSubscriptionEnded(NOW)).isFalse();
        assertThat(pending().pauseBecauseSubscriptionEnded(NOW)).isFalse();
    }

    @Test
    void reservingMovesMoneyOutOfTheBalanceSoItCannotBeSpentTwice() {
        SavingsGoal goal = activeWith(100);

        goal.reserve(Money.usd(70), NOW);
        assertThat(goal.getCurrentBalance()).isEqualTo(Money.usd(30));
        assertThat(goal.getReservedBalance()).isEqualTo(Money.usd(70));

        assertThatThrownBy(() -> goal.reserve(Money.usd(40), NOW))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Insufficient");
        assertThat(goal.getReservedBalance()).isEqualTo(Money.usd(70));
    }

    @Test
    void settlingSpendsTheReserveAndReleasingGivesItBack() {
        SavingsGoal goal = activeWith(100);
        goal.reserve(Money.usd(40), NOW);
        goal.reserve(Money.usd(10), NOW);

        goal.settleReserve(Money.usd(40), NOW);
        assertThat(goal.getReservedBalance()).isEqualTo(Money.usd(10));
        assertThat(goal.getCurrentBalance()).isEqualTo(Money.usd(50));

        goal.releaseReserve(Money.usd(10), NOW);
        assertThat(goal.getReservedBalance()).isEqualTo(Money.zeroUsd());
        assertThat(goal.getCurrentBalance()).isEqualTo(Money.usd(60));
        assertThatThrownBy(() -> goal.releaseReserve(Money.usd(1), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPayoutTakenBackAfterItArrivedPutsTheMoneyBackInTheGoal() {
        SavingsGoal goal = activeWith(100);
        goal.reserve(Money.usd(40), NOW);
        goal.settleReserve(Money.usd(40), NOW);

        goal.restoreWithdrawn(Money.usd(40), NOW);

        assertThat(goal.getCurrentBalance()).isEqualTo(Money.usd(100));
        assertThat(goal.getReservedBalance()).isEqualTo(Money.zeroUsd());
    }

    @Test
    void reservingFromAGoalNeverApprovedIsRefused() {
        assertThatThrownBy(() -> pending().reserve(Money.usd(5), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aGoalWhoseSubscriptionEndedNeedsANewApprovalToResume() {
        SavingsGoal goal = activeWith(25);
        goal.pauseBecauseSubscriptionEnded(NOW);

        goal.requireNewApproval(NOW.plusSeconds(60), NOW);

        assertThat(goal.getStatus()).isEqualTo(GoalStatus.PENDING_APPROVAL);
        assertThat(goal.acceptsDeposits()).isFalse();
        assertThatThrownBy(() -> goal.requireNewApproval(NOW, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pausingOrResumingBeforeApprovalIsRefused() {
        SavingsGoal goal = pending();
        assertThatThrownBy(goal::pause).isInstanceOf(IllegalStateException.class).hasMessageContaining("Approve");
        assertThatThrownBy(goal::resume).isInstanceOf(IllegalStateException.class).hasMessageContaining("Approve");
    }
}
