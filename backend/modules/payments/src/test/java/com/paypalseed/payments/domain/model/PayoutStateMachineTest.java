package com.paypalseed.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import com.paypalseed.payments.api.dto.PaymentsDtos.SendPayout;
import com.paypalseed.payments.api.dto.PaymentsDtos.StartSavingsSubscription;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutStateMachineTest {

    @Test
    void forwardPathsAreAllowed() {
        assertThat(PayoutStateMachine.apply(PayoutStatus.REQUESTED, PayoutStatus.PENDING)).contains(PayoutStatus.PENDING);
        assertThat(PayoutStateMachine.apply(PayoutStatus.PENDING, PayoutStatus.SUCCEEDED)).contains(PayoutStatus.SUCCEEDED);
        assertThat(PayoutStateMachine.apply(PayoutStatus.PENDING, PayoutStatus.UNCLAIMED)).contains(PayoutStatus.UNCLAIMED);
        assertThat(PayoutStateMachine.apply(PayoutStatus.UNCLAIMED, PayoutStatus.RETURNED)).contains(PayoutStatus.RETURNED);
        assertThat(PayoutStateMachine.apply(PayoutStatus.UNCLAIMED, PayoutStatus.SUCCEEDED)).contains(PayoutStatus.SUCCEEDED);
        assertThat(PayoutStateMachine.apply(PayoutStatus.SUCCEEDED, PayoutStatus.RETURNED)).contains(PayoutStatus.RETURNED);
    }

    @Test
    void repeatsAndContradictionsAreIgnored() {
        assertThat(PayoutStateMachine.apply(PayoutStatus.SUCCEEDED, PayoutStatus.SUCCEEDED)).isEmpty();
        assertThat(PayoutStateMachine.apply(PayoutStatus.SUCCEEDED, PayoutStatus.PENDING)).isEmpty();
        assertThat(PayoutStateMachine.apply(PayoutStatus.SUCCEEDED, PayoutStatus.FAILED)).isEmpty();
        assertThat(PayoutStateMachine.apply(PayoutStatus.PENDING, PayoutStatus.PENDING)).isEmpty();
        assertThat(PayoutStateMachine.apply(PayoutStatus.PENDING, PayoutStatus.REQUESTED)).isEmpty();
    }

    @Test
    void finalStatesNeverMove() {
        for (PayoutStatus finalState : new PayoutStatus[] {PayoutStatus.FAILED, PayoutStatus.BLOCKED, PayoutStatus.RETURNED}) {
            assertThat(finalState.isFinal()).isTrue();
            for (PayoutStatus other : PayoutStatus.values()) {
                assertThat(PayoutStateMachine.canMove(finalState, other)).as(finalState + " to " + other).isFalse();
            }
        }
    }

    @Test
    void paypalWordsMapToOurStates() {
        assertThat(PayoutStateMachine.fromPaypalItem("SUCCESS")).contains(PayoutStatus.SUCCEEDED);
        assertThat(PayoutStateMachine.fromPaypalItem("ONHOLD")).contains(PayoutStatus.PENDING);
        assertThat(PayoutStateMachine.fromPaypalItem("REFUNDED")).contains(PayoutStatus.RETURNED);
        assertThat(PayoutStateMachine.fromPaypalItem("SOMETHING_NEW")).isEqualTo(Optional.empty());
        assertThat(PayoutStateMachine.fromPaypalBatch("DENIED")).contains(PayoutStatus.FAILED);
        assertThat(PayoutStateMachine.fromPaypalBatch("PROCESSING")).contains(PayoutStatus.PENDING);
        assertThat(PayoutStateMachine.fromPaypalBatch("SUCCESS")).isEmpty();
    }

    @Test
    void payoutRequestsAreValidatedAtTheEdge() {
        assertThatThrownBy(() -> new SendPayout("r", 0, RecipientType.EMAIL, "a@b.test", PayoutWallet.PAYPAL, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SendPayout(" ", 100, RecipientType.EMAIL, "a@b.test", PayoutWallet.PAYPAL, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SendPayout("r".repeat(57), 100, RecipientType.EMAIL, "a@b.test", PayoutWallet.PAYPAL, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SendPayout("r", 100, RecipientType.EMAIL, "", PayoutWallet.PAYPAL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void savingsSubscriptionsNeedWholeDollars() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new StartSavingsSubscription(id, id, 2_550, "r", "u", "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StartSavingsSubscription(id, id, 0, "r", "u", "c")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new StartSavingsSubscription(id, id, 2_500, "r", "u", "c").monthlyCents()).isEqualTo(2_500);
    }
}
