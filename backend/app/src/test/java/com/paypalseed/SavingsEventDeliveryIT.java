package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.api.events.GoalCreatedEvent;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import com.paypalseed.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Regression: {@code CreateGoalUseCase} was not transactional, so {@code GoalCreatedEvent} was published outside any
 * transaction and never reached an {@code @ApplicationModuleListener} (the listeners of desk-query and fraud stayed
 * incomplete). Every savings event must reach such a listener when the goal is driven through HTTP.
 */
@Import({IntegrationTest.Fakes.class, SavingsEventDeliveryIT.Recording.class})
class SavingsEventDeliveryIT extends MoneyFlowSupport {

    public static class Received {
        final List<GoalCreatedEvent> goals = new CopyOnWriteArrayList<>();
        final List<DepositReceivedEvent> deposits = new CopyOnWriteArrayList<>();
        final List<WithdrawalProcessedEvent> withdrawals = new CopyOnWriteArrayList<>();

        @ApplicationModuleListener
        public void on(GoalCreatedEvent e) {
            goals.add(e);
        }

        @ApplicationModuleListener
        public void on(DepositReceivedEvent e) {
            deposits.add(e);
        }

        @ApplicationModuleListener
        public void on(WithdrawalProcessedEvent e) {
            withdrawals.add(e);
        }

        // The listener is proxied (it is transactional and asynchronous), so the test reads through these methods.
        public List<GoalCreatedEvent> goals() {
            return goals;
        }

        public List<DepositReceivedEvent> deposits() {
            return deposits;
        }

        public List<WithdrawalProcessedEvent> withdrawals() {
            return withdrawals;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {
        @Bean
        Received received() {
            return new Received();
        }
    }

    @Autowired
    Received received;

    @Test
    void aGoalCreatedWithAClientSubscriptionIdAnnouncesItselfToTransactionalListeners() throws Exception {
        UUID member = newMember();

        JsonNode goal = legacyGoal(member, 25);
        settle();

        assertThat(received.goals()).filteredOn(e -> e.goalId().toString().equals(goal.path("id").asText())).singleElement()
            .satisfies(e -> assertThat(e.memberId()).isEqualTo(member.toString()));
    }

    @Test
    void aGoalCreatedWithABackendSubscriptionAnnouncesItselfToo() throws Exception {
        UUID member = newMember();

        JsonNode goal = read(mvc.perform(asMember(post("/api/v1/savings/goals"), member).header("Idempotency-Key", "event-goal")
            .content("{\"name\":\"Mixer\",\"targetAmount\":500.00,\"monthlyAmount\":25}")).andExpect(status().isCreated()).andReturn());
        settle();

        assertThat(received.goals()).filteredOn(e -> e.goalId().toString().equals(goal.path("id").asText())).hasSize(1);
    }

    @Test
    void depositsAndWithdrawalsAnnounceThemselvesToo() throws Exception {
        UUID member = newMember();
        String goalId = legacyGoal(member, 25).path("id").asText();

        legacyTopUp(member, goalId, 40);
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member)
            .content("{\"amount\":10,\"payPalPayoutId\":\"legacy-payout-" + goalId + "\"}")).andExpect(status().isOk());
        settle();

        assertThat(received.deposits()).filteredOn(e -> e.goalId().toString().equals(goalId)).hasSize(1);
        assertThat(received.withdrawals()).filteredOn(e -> e.goalId().toString().equals(goalId)).hasSize(1);
    }
}
