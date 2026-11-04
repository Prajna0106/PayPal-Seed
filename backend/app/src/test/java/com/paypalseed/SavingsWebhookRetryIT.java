package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paypalseed.payments.api.events.PaymentEvents.SavingsDepositReceived;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsWallet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A savings webhook can beat the commit of the goal it names (ADR 0027). The listener then fails, so the event publication
 * stays incomplete and is delivered again; a goal that never appears is dropped once the event is older than the
 * visibility window, so nothing loops for ever.
 */
@TestPropertySource(properties = "seed.savings.goal-visibility-window=PT1H")
class SavingsWebhookRetryIT extends MoneyFlowSupport {

    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    SavingsGoalRepository goals;

    private void publish(UUID goalId, UUID member, String saleId, Instant happenedAt) {
        tx.executeWithoutResult(status -> publisher.publishEvent(new SavingsDepositReceived(UUID.randomUUID(), goalId, member,
                "I-" + goalId, saleId, 2_500, 90, happenedAt, "test")));
    }

    /** Deposit announcements for this sale that are still waiting to be delivered successfully. */
    private int incomplete(String saleId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM event_publication
                 WHERE completion_date IS NULL AND event_type LIKE '%SavingsDepositReceived' AND serialized_event LIKE ?
                """).param("%" + saleId + "%").query(Integer.class).single();
    }

    private int credited(String saleId) {
        return jdbc.sql("SELECT COUNT(*) FROM savings.credited_payment WHERE paypal_txn_id = ?").param(saleId).query(Integer.class).single();
    }

    /** A Venmo goal is active at once, so a deposit can reach it as soon as it exists. */
    private void commitGoal(UUID goalId, UUID member) {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> goals.save(SavingsGoal.createForSubscription(goalId, member.toString(), "Mixer",
                Money.usd(500), Money.usd(25), null, SavingsWallet.VENMO, "retry-" + goalId, null, now)));
    }

    private double balanceOf(UUID goalId, UUID member) throws Exception {
        return goal(member, goalId.toString()).path("currentBalance").asDouble();
    }

    @Test
    void aDepositThatBeatsTheGoalsCommitIsRetriedAndAppliedOnceTheGoalAppears() throws Exception {
        UUID member = newMember();
        UUID goalId = UUID.randomUUID();
        String saleId = "SALE-EARLY-" + UUID.randomUUID();

        publish(goalId, member, saleId, Instant.now());

        // Delivered, refused and kept: it is not completed (a dropped event would be), and nothing is credited.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(() -> incomplete(saleId) == 1 && credited(saleId) == 0);

        commitGoal(goalId, member);
        settle();

        assertThat(credited(saleId)).isEqualTo(1);
        assertThat(balanceOf(goalId, member)).isEqualTo(25.0);
        assertLedgerBalanced();

        // The same sale delivered again changes nothing.
        publish(goalId, member, saleId, Instant.now());
        settle();
        assertThat(credited(saleId)).isEqualTo(1);
        assertThat(balanceOf(goalId, member)).isEqualTo(25.0);
    }

    @Test
    void aDepositForAGoalThatNeverAppearsIsDroppedOnceItIsOlderThanTheWindow() {
        UUID member = newMember();
        UUID goalId = UUID.randomUUID();
        String saleId = "SALE-UNKNOWN-" + UUID.randomUUID();

        publish(goalId, member, saleId, Instant.now().minus(Duration.ofHours(2)));
        settle();

        assertThat(incomplete(saleId)).as("completed, not delivered again").isZero();
        assertThat(credited(saleId)).isZero();
        assertThat(goals.findById(goalId)).isEmpty();
    }

    @Test
    void aDropIsFinalEvenIfTheGoalAppearsLater() throws Exception {
        UUID member = newMember();
        UUID goalId = UUID.randomUUID();
        String saleId = "SALE-LATE-" + UUID.randomUUID();
        publish(goalId, member, saleId, Instant.now().minus(Duration.ofHours(2)));
        settle();

        commitGoal(goalId, member);
        settle();

        assertThat(credited(saleId)).as("the drop was logged and is not retried").isZero();
        assertThat(balanceOf(goalId, member)).isZero();
    }
}
