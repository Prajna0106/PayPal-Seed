package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.support.PayPalWebhooks;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PayPal does not deliver webhooks in order (ADR 0027). A savings subscription report is dated by the resource's
 * {@code update_time}, else the webhook's {@code create_time}, else the moment it was received; the goal and the stored
 * subscription status both refuse to go back to an older state.
 */
class SavingsWebhookOrderingIT extends MoneyFlowSupport {

    private JsonNode approvedGoal(UUID member, String key) throws Exception {
        JsonNode created = read(mvc.perform(asMember(post("/api/v1/savings/goals"), member).header("Idempotency-Key", key).content("""
                {"name":"Mixer","targetAmount":500.00,"monthlyAmount":25,"wallet":"PAYPAL"}
                """)).andExpect(status().isCreated()).andReturn());
        String subscriptionId = created.path("payPalSubscriptionId").asText();
        paypal.approve(subscriptionId);
        subscriptionWebhook("BILLING.SUBSCRIPTION.ACTIVATED", subscriptionId, "ACTIVE");
        settle();
        return goal(member, created.path("id").asText());
    }

    /** A subscription report with exactly the times given; a null time is left out of the JSON altogether. */
    private void report(JsonNode goal, String type, String status, Instant webhookCreateTime, Instant updateTime) throws Exception {
        String eventTime = webhookCreateTime == null ? "" : ",\"create_time\":\"" + webhookCreateTime + "\"";
        String resourceTime = updateTime == null ? "" : ",\"update_time\":\"" + updateTime + "\"";
        webhook("{\"id\":\"" + PayPalWebhooks.newEventId() + "\",\"event_version\":\"1.0\",\"event_type\":\"" + type + "\"" + eventTime
                + ",\"resource\":{\"id\":\"" + goal.path("payPalSubscriptionId").asText() + "\",\"status\":\"" + status
                + "\",\"custom_id\":\"goal:" + goal.path("id").asText() + "\"" + resourceTime + "}}");
        settle();
    }

    private String goalStatus(UUID member, JsonNode goal) throws Exception {
        return goal(member, goal.path("id").asText()).path("status").asText();
    }

    private String storedStatus(JsonNode goal) {
        return jdbc.sql("SELECT status FROM payments.savings_subscription WHERE paypal_subscription_id = ?")
                .param(goal.path("payPalSubscriptionId").asText()).query(String.class).single();
    }

    private void pauseAndResume(UUID member, JsonNode goal) throws Exception {
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/pause", goal.path("id").asText()), member)).andExpect(status().isOk());
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/resume", goal.path("id").asText()), member)).andExpect(status().isOk());
        settle();
    }

    @Test
    void aSuspendedMadeInPayPalBeforeTheMemberResumedInSeedDoesNotPauseTheGoalAgain() throws Exception {
        UUID member = newMember();
        JsonNode goal = approvedGoal(member, "order-1");
        Instant beforeTheResume = Instant.now();
        pauseAndResume(member, goal);
        assertThat(goalStatus(member, goal)).isEqualTo("ACTIVE");
        assertThat(storedStatus(goal)).isEqualTo("ACTIVE");

        report(goal, "BILLING.SUBSCRIPTION.SUSPENDED", "SUSPENDED", beforeTheResume, beforeTheResume);

        assertThat(goalStatus(member, goal)).as("the older report is ignored").isEqualTo("ACTIVE");
        assertThat(storedStatus(goal)).as("and the stored status does not go back either").isEqualTo("ACTIVE");
    }

    @Test
    void aSuspendedMadeInPayPalAfterTheResumeIsApplied() throws Exception {
        UUID member = newMember();
        JsonNode goal = approvedGoal(member, "order-2");
        pauseAndResume(member, goal);

        Instant later = Instant.now().plus(Duration.ofMinutes(5));
        report(goal, "BILLING.SUBSCRIPTION.SUSPENDED", "SUSPENDED", later, later);

        assertThat(goalStatus(member, goal)).isEqualTo("PAUSED");
        assertThat(storedStatus(goal)).isEqualTo("SUSPENDED");
    }

    @Test
    void withoutAnUpdateTimeTheWebhooksCreateTimeDecides() throws Exception {
        UUID member = newMember();
        JsonNode goal = approvedGoal(member, "order-3");
        Instant beforeTheResume = Instant.now();
        pauseAndResume(member, goal);

        report(goal, "BILLING.SUBSCRIPTION.SUSPENDED", "SUSPENDED", beforeTheResume, null);
        assertThat(goalStatus(member, goal)).as("created before the resume: ignored").isEqualTo("ACTIVE");
        assertThat(storedStatus(goal)).isEqualTo("ACTIVE");

        report(goal, "BILLING.SUBSCRIPTION.SUSPENDED", "SUSPENDED", Instant.now().plus(Duration.ofMinutes(5)), null);
        assertThat(goalStatus(member, goal)).as("created after the resume: applied").isEqualTo("PAUSED");
    }

    @Test
    void withNoTimeAtAllTheOrderOfReceiptDecides() throws Exception {
        UUID member = newMember();
        JsonNode goal = approvedGoal(member, "order-4");
        pauseAndResume(member, goal);

        // Received after the resume, so it is the newest thing that happened to the subscription.
        report(goal, "BILLING.SUBSCRIPTION.SUSPENDED", "SUSPENDED", null, null);

        assertThat(goalStatus(member, goal)).isEqualTo("PAUSED");
        assertThat(storedStatus(goal)).isEqualTo("SUSPENDED");
    }

    @Test
    void anOlderReportNeverMovesTheStoredStatusBackAndACancelledSubscriptionStaysCancelled() throws Exception {
        UUID member = newMember();
        JsonNode goal = approvedGoal(member, "order-5");
        Instant t1 = Instant.now().plus(Duration.ofMinutes(1));
        Instant t2 = t1.plus(Duration.ofMinutes(1));
        Instant t3 = t2.plus(Duration.ofMinutes(1));

        report(goal, "BILLING.SUBSCRIPTION.SUSPENDED", "SUSPENDED", t2, t2);
        assertThat(storedStatus(goal)).isEqualTo("SUSPENDED");

        report(goal, "BILLING.SUBSCRIPTION.RE-ACTIVATED", "ACTIVE", t1, t1);
        assertThat(storedStatus(goal)).as("reported before the suspension: stale").isEqualTo("SUSPENDED");
        assertThat(goalStatus(member, goal)).isEqualTo("PAUSED");

        report(goal, "BILLING.SUBSCRIPTION.CANCELLED", "CANCELLED", t3, t3);
        assertThat(storedStatus(goal)).isEqualTo("CANCELLED");

        Instant t4 = t3.plus(Duration.ofMinutes(1));
        report(goal, "BILLING.SUBSCRIPTION.RE-ACTIVATED", "ACTIVE", t4, t4);
        assertThat(storedStatus(goal)).as("PayPal cannot bring a cancelled subscription back").isEqualTo("CANCELLED");
    }
}
