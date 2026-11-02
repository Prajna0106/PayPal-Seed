package com.paypalseed.savings.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.domain.model.SavingsWallet;
import com.paypalseed.savings.domain.model.Withdrawal;
import com.paypalseed.savings.domain.model.WithdrawalStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The withdrawal state machine: REQUESTED can end three ways, SUCCEEDED can only be taken back, the rest is final. */
class WithdrawalStatusTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private static Withdrawal requested() {
        return Withdrawal.request(UUID.randomUUID(), UUID.randomUUID(), "m", "k", Money.usd(25), SavingsWallet.PAYPAL, NOW);
    }

    @Test
    void legalMovesAreExactlyTheseAndNoOthers() {
        for (WithdrawalStatus from : WithdrawalStatus.values()) {
            for (WithdrawalStatus to : WithdrawalStatus.values()) {
                boolean expected = switch (from) {
                    case REQUESTED -> to == WithdrawalStatus.SUCCEEDED || to == WithdrawalStatus.FAILED || to == WithdrawalStatus.RETURNED;
                    case SUCCEEDED -> to == WithdrawalStatus.RETURNED;
                    case FAILED, RETURNED -> false;
                };
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(expected);
            }
        }
    }

    @Test
    void finalStatesAreFailedAndReturned() {
        assertThat(WithdrawalStatus.FAILED.isFinal()).isTrue();
        assertThat(WithdrawalStatus.RETURNED.isFinal()).isTrue();
        assertThat(WithdrawalStatus.REQUESTED.isFinal()).isFalse();
        assertThat(WithdrawalStatus.SUCCEEDED.isFinal()).isFalse();
    }

    @Test
    void movingToTheStateItIsAlreadyInChangesNothing() {
        Withdrawal w = requested();
        assertThat(w.moveTo(WithdrawalStatus.SUCCEEDED, null, "TXN-1", NOW)).isTrue();
        assertThat(w.moveTo(WithdrawalStatus.SUCCEEDED, null, "TXN-2", NOW.plusSeconds(5))).isFalse();
        assertThat(w.getPaypalTransactionId()).isEqualTo("TXN-1");
        assertThat(w.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void anIllegalMoveThrowsAndLeavesTheWithdrawalAlone() {
        Withdrawal w = requested();
        w.moveTo(WithdrawalStatus.FAILED, "RECEIVER_UNREGISTERED", null, NOW);

        assertThatThrownBy(() -> w.moveTo(WithdrawalStatus.SUCCEEDED, null, "TXN", NOW))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("FAILED").hasMessageContaining("SUCCEEDED");
        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(w.getFailureReason()).isEqualTo("RECEIVER_UNREGISTERED");
    }

    @Test
    void aReplayMatchesOnlyWithTheSameAmountAndWallet() {
        Withdrawal w = requested();
        assertThat(w.matches(Money.usd(25), SavingsWallet.PAYPAL)).isTrue();
        assertThat(w.matches(Money.usd(26), SavingsWallet.PAYPAL)).isFalse();
        assertThat(w.matches(Money.usd(25), SavingsWallet.VENMO)).isFalse();
    }
}
