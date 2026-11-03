package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A goal whose PayPal webhooks never came (a machine PayPal cannot call, or a lost delivery) is brought up to date by
 * reading PayPal (ADR 0028). The read goes through the state and crediting steps the webhooks use, so a webhook that
 * comes before, after or twice credits nothing twice.
 */
class SavingsSyncIT extends MoneyFlowSupport {

    private JsonNode pendingGoal(UUID member, String key) throws Exception {
        return read(mvc.perform(asMember(post("/api/v1/savings/goals"), member).header("Idempotency-Key", key).content("""
                {"name":"Mixer","targetAmount":500.00,"monthlyAmount":25,"wallet":"PAYPAL"}
                """)).andExpect(status().isCreated()).andReturn());
    }

    private MvcResult syncRequest(UUID member, String goalId) throws Exception {
        return mvc.perform(asMember(post("/api/v1/savings/goals/{id}/sync", goalId), member)).andReturn();
    }

    private JsonNode sync(UUID member, String goalId) throws Exception {
        MvcResult result = syncRequest(member, goalId);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    /** The five second gap is real time; a test that reads twice lifts it by forgetting the last read. */
    private void forgetLastRead(String goalId) {
        jdbc.sql("UPDATE savings.savings_goal SET last_synced_at = NULL WHERE id = ?").param(UUID.fromString(goalId)).update();
    }

    private String statusOf(UUID member, JsonNode g) throws Exception {
        return goal(member, g.path("id").asText()).path("status").asText();
    }

    private long creditedPayments(String goalId) {
        return jdbc.sql("SELECT COUNT(*) FROM savings.credited_payment WHERE goal_id = ?").param(UUID.fromString(goalId))
                .query(Long.class).single();
    }

    private String storedSubscriptionStatus(String subscriptionId) {
        return jdbc.sql("SELECT status FROM payments.savings_subscription WHERE paypal_subscription_id = ?")
                .param(subscriptionId).query(String.class).single();
    }

    @Test
    void anApprovalAndTheFirstSaleThatNoWebhookReportedActivateTheGoalAndCreditItOnce() throws Exception {
        UUID member = newMember();
        JsonNode goal = pendingGoal(member, "sync-1");
        String id = goal.path("id").asText();
        String subscriptionId = goal.path("payPalSubscriptionId").asText();
        assertThat(goal.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        paypal.approve(subscriptionId);
        paypal.addSale(subscriptionId, "SALE-SYNC-1", 2_500);

        JsonNode synced = sync(member, id);

        assertThat(synced.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(synced.path("currentBalance").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(synced.path("id").asText()).isEqualTo(id);
        assertThat(goal(member, id).path("currentBalance").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(memberSavingsInBooks(member)).isEqualTo(2_500);
        assertThat(storedSubscriptionStatus(subscriptionId)).isEqualTo("ACTIVE");

        forgetLastRead(id);
        JsonNode again = sync(member, id);

        assertThat(again.path("currentBalance").decimalValue()).as("the same sale is not credited again").isEqualByComparingTo("25.00");
        assertThat(creditedPayments(id)).isEqualTo(1);
        assertThat(memberSavingsInBooks(member)).isEqualTo(2_500);
        assertLedgerBalanced();
    }

    @Test
    void aWebhookThatCameFirstIsNotCreditedAgainByTheRead() throws Exception {
        UUID member = newMember();
        JsonNode goal = pendingGoal(member, "sync-2");
        String id = goal.path("id").asText();
        String subscriptionId = goal.path("payPalSubscriptionId").asText();
        paypal.approve(subscriptionId);
        subscriptionWebhook("BILLING.SUBSCRIPTION.ACTIVATED", subscriptionId, "ACTIVE");
        saleWebhook(newEventId(), subscriptionId, "SALE-SYNC-2", 2_500);
        settle();
        assertThat(goal(member, id).path("currentBalance").decimalValue()).isEqualByComparingTo("25.00");
        paypal.addSale(subscriptionId, "SALE-SYNC-2", 2_500);

        JsonNode synced = sync(member, id);

        assertThat(synced.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(synced.path("currentBalance").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(creditedPayments(id)).isEqualTo(1);
        assertThat(memberSavingsInBooks(member)).isEqualTo(2_500);
    }

    @Test
    void aWebhookThatComesAfterTheReadCreditsNothingMore() throws Exception {
        UUID member = newMember();
        JsonNode goal = pendingGoal(member, "sync-3");
        String id = goal.path("id").asText();
        String subscriptionId = goal.path("payPalSubscriptionId").asText();
        paypal.approve(subscriptionId);
        paypal.addSale(subscriptionId, "SALE-SYNC-3", 2_500);
        sync(member, id);

        subscriptionWebhook("BILLING.SUBSCRIPTION.ACTIVATED", subscriptionId, "ACTIVE");
        String eventId = newEventId();
        saleWebhook(eventId, subscriptionId, "SALE-SYNC-3", 2_500);
        saleWebhook(eventId, subscriptionId, "SALE-SYNC-3", 2_500); // PayPal delivers it twice
        settle();

        JsonNode after = goal(member, id);
        assertThat(after.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(after.path("currentBalance").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(creditedPayments(id)).isEqualTo(1);
        assertThat(memberSavingsInBooks(member)).isEqualTo(2_500);
    }

    @Test
    void aSubscriptionNotApprovedYetLeavesTheGoalWaiting() throws Exception {
        UUID member = newMember();
        JsonNode goal = pendingGoal(member, "sync-4");

        JsonNode synced = sync(member, goal.path("id").asText());

        assertThat(synced.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(synced.path("approvalUrl").asText()).isNotBlank();
        assertThat(synced.path("currentBalance").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void aSubscriptionCancelledInPayPalPausesTheGoal() throws Exception {
        UUID member = newMember();
        JsonNode goal = pendingGoal(member, "sync-5");
        String id = goal.path("id").asText();
        String subscriptionId = goal.path("payPalSubscriptionId").asText();
        paypal.approve(subscriptionId);
        assertThat(sync(member, id).path("status").asText()).isEqualTo("ACTIVE");

        paypal.subscriptions.get(subscriptionId).status = "CANCELLED";
        paypal.subscriptions.get(subscriptionId).statusChangedAt = Instant.now().plus(Duration.ofMinutes(1));
        forgetLastRead(id);
        JsonNode synced = sync(member, id);

        assertThat(synced.path("status").asText()).isEqualTo("PAUSED");
        assertThat(storedSubscriptionStatus(subscriptionId)).isEqualTo("CANCELLED");
    }

    @Test
    void aReportOlderThanTheMembersOwnResumeDoesNotPauseTheGoalAgain() throws Exception {
        UUID member = newMember();
        JsonNode goal = pendingGoal(member, "sync-6");
        String id = goal.path("id").asText();
        String subscriptionId = goal.path("payPalSubscriptionId").asText();
        paypal.approve(subscriptionId);
        sync(member, id);
        Instant beforeTheResume = Instant.now();
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/pause", id), member)).andExpect(status().isOk());
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/resume", id), member)).andExpect(status().isOk());

        paypal.subscriptions.get(subscriptionId).status = "SUSPENDED";
        paypal.subscriptions.get(subscriptionId).statusChangedAt = beforeTheResume;
        forgetLastRead(id);

        assertThat(sync(member, id).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void anotherMembersGoalIsNotFoundAndNoSessionIsRefused() throws Exception {
        UUID owner = newMember();
        UUID other = newMember();
        JsonNode goal = pendingGoal(owner, "sync-7");
        String id = goal.path("id").asText();
        long reads = paypal.callCount("getSubscription");

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/sync", id), other)).andExpect(status().isNotFound());
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/sync", UUID.randomUUID()), owner)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/savings/goals/{id}/sync", id)).andExpect(status().isUnauthorized());

        assertThat(paypal.callCount("getSubscription")).as("PayPal was never asked").isEqualTo(reads);
    }

    @Test
    void aSecondReadWithinFiveSecondsReturnsTheStoredGoalWithoutAskingPayPal() throws Exception {
        UUID member = newMember();
        JsonNode goal = pendingGoal(member, "sync-8");
        String id = goal.path("id").asText();
        String subscriptionId = goal.path("payPalSubscriptionId").asText();
        assertThat(sync(member, id).path("status").asText()).isEqualTo("PENDING_APPROVAL");
        long reads = paypal.callCount("getSubscription");

        paypal.approve(subscriptionId);
        paypal.addSale(subscriptionId, "SALE-SYNC-8", 2_500);
        JsonNode second = sync(member, id);

        assertThat(second.path("status").asText()).as("untouched").isEqualTo("PENDING_APPROVAL");
        assertThat(paypal.callCount("getSubscription")).isEqualTo(reads);

        forgetLastRead(id);
        assertThat(sync(member, id).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void aPayPalOutageIsAnErrorAndLeavesTheGoalAsItWas() throws Exception {
        UUID member = newMember();
        JsonNode goal = pendingGoal(member, "sync-9");
        String id = goal.path("id").asText();
        paypal.approve(goal.path("payPalSubscriptionId").asText());
        paypal.failNext("getSubscription", com.paypalseed.support.FakePayPal.unavailable());

        MvcResult failed = syncRequest(member, id);

        assertThat(failed.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        assertThat(goal(member, id).path("status").asText()).isEqualTo("PENDING_APPROVAL");
        forgetLastRead(id);
        assertThat(sync(member, id).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void theJobReadsEachGoalThatMayHaveMissedAWebhookAndSkipsOneThatFails() throws Exception {
        UUID first = newMember();
        UUID second = newMember();
        UUID late = newMember();
        UUID busy = newMember();
        JsonNode a = pendingGoal(first, "job-a");
        JsonNode b = pendingGoal(second, "job-b");
        JsonNode expired = pendingGoal(late, "job-c");
        JsonNode recentlyRead = pendingGoal(busy, "job-d");
        for (JsonNode g : new JsonNode[] {a, b}) {
            paypal.approve(g.path("payPalSubscriptionId").asText());
            paypal.addSale(g.path("payPalSubscriptionId").asText(), "SALE-" + g.path("id").asText(), 2_500);
        }
        // The job leaves out a goal whose approval window closed, and an active goal read in the last hour.
        jdbc.sql("UPDATE savings.savings_goal SET approval_expires_at = TIMESTAMPTZ '2000-01-01 00:00:00+00' WHERE id = ?")
                .param(UUID.fromString(expired.path("id").asText())).update();
        paypal.approve(recentlyRead.path("payPalSubscriptionId").asText());
        assertThat(sync(busy, recentlyRead.path("id").asText()).path("status").asText()).isEqualTo("ACTIVE");
        // Every other goal of this class is out of the way, so the batch is exactly the two above.
        jdbc.sql("UPDATE savings.savings_goal SET status = 'CANCELLED' WHERE id NOT IN (?, ?, ?, ?)")
                .param(UUID.fromString(a.path("id").asText())).param(UUID.fromString(b.path("id").asText()))
                .param(UUID.fromString(expired.path("id").asText())).param(UUID.fromString(recentlyRead.path("id").asText()))
                .update();
        paypal.failNext("getSubscription", com.paypalseed.support.FakePayPal.unavailable());

        JsonNode run = read(mvc.perform(post("/internal/jobs/savings/reconcile").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk()).andReturn());

        assertThat(run.path("examined").asInt()).isEqualTo(2);
        assertThat(run.path("failed").asInt()).isEqualTo(1);
        assertThat(run.path("synced").asInt()).isEqualTo(1);
        int active = (statusOf(first, a).equals("ACTIVE") ? 1 : 0) + (statusOf(second, b).equals("ACTIVE") ? 1 : 0);
        assertThat(active).as("the goal that was read is active, the one that failed still waits").isEqualTo(1);

        // The next run (the failed goal's gap lifted) finishes the other one; the first is not credited twice.
        jdbc.sql("UPDATE savings.savings_goal SET last_synced_at = NULL").update();
        JsonNode second2 = read(mvc.perform(post("/internal/jobs/savings/reconcile").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk()).andReturn());

        assertThat(second2.path("failed").asInt()).isZero();
        assertThat(goal(first, a.path("id").asText()).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(goal(second, b.path("id").asText()).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(creditedPayments(a.path("id").asText())).isEqualTo(1);
        assertThat(creditedPayments(b.path("id").asText())).isEqualTo(1);
        assertThat(goal(late, expired.path("id").asText()).path("status").asText()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void theJobIsForTheServiceTokenOnly() throws Exception {
        UUID member = newMember();

        mvc.perform(asMember(post("/internal/jobs/savings/reconcile"), member)).andExpect(status().is4xxClientError());
        mvc.perform(post("/internal/jobs/savings/reconcile")).andExpect(status().is4xxClientError());
        mvc.perform(post("/internal/jobs/savings/reconcile?limit=0").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isBadRequest());
    }
}
