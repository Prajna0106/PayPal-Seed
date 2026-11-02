package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PayPal does not deliver webhooks in the order things happened. A report about an earlier subscription state must not
 * undo a later one: a SUSPENDED that arrives after the member resumed the goal cannot pause it again. The reports
 * carry their own time (the resource's {@code update_time}; its {@code create_time} is when the subscription began and never changes), and Seed's own pause and resume count as states.
 */
class SavingsSubscriptionOrderIT extends MoneyFlowSupport {

    private String subscriptionEvent(String type, String subscriptionId, String status, Instant happenedAt) {
        return """
            {"id":"%s","event_version":"1.0","create_time":"%s","event_type":"%s",
             "resource":{"id":"%s","status":"%s","create_time":"2020-01-01T00:00:00Z","update_time":"%s"}}
            """.formatted(newEventId(), Instant.now(), type, subscriptionId, status, happenedAt);
    }

    private JsonNode activeGoal(UUID member, String key, Instant approvedAt) throws Exception {
        JsonNode created = read(mvc.perform(asMember(post("/api/v1/savings/goals"), member).header("Idempotency-Key", key)
            .content("{\"name\":\"Mixer\",\"targetAmount\":500.00,\"monthlyAmount\":25,\"wallet\":\"PAYPAL\"}"))
            .andExpect(status().isCreated()).andReturn());
        String subscriptionId = created.path("payPalSubscriptionId").asText();
        paypal.approve(subscriptionId);
        webhook(subscriptionEvent("BILLING.SUBSCRIPTION.ACTIVATED", subscriptionId, "ACTIVE", approvedAt));
        settle();
        assertThat(goal(member, created.path("id").asText()).path("status").asText()).isEqualTo("ACTIVE");
        return created;
    }

    private String statusOf(UUID member, JsonNode created) throws Exception {
        return goal(member, created.path("id").asText()).path("status").asText();
    }

    private Instant stateStampOf(JsonNode created) {
        return jdbc.sql("SELECT subscription_state_at FROM savings.savings_goal WHERE id = ?")
            .param(UUID.fromString(created.path("id").asText())).query(OffsetDateTime.class).single().toInstant();
    }

    @Test
    void aSuspendedReportThatHappenedBeforeTheMemberResumedDoesNotPauseTheGoalAgain() throws Exception {
        UUID member = newMember();
        Instant base = Instant.now();
        JsonNode created = activeGoal(member, "order-resume", base.minus(Duration.ofHours(2)));
        String subscriptionId = created.path("payPalSubscriptionId").asText();
        String goalId = created.path("id").asText();

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/pause", goalId), member)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"));
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/resume", goalId), member)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        // PayPal's report of the earlier suspension finally arrives.
        webhook(subscriptionEvent("BILLING.SUBSCRIPTION.SUSPENDED", subscriptionId, "SUSPENDED", base.minus(Duration.ofHours(1))));
        settle();

        assertThat(statusOf(member, created)).as("the resume came later than the suspension that was reported late").isEqualTo("ACTIVE");
        assertThat(stateStampOf(created)).as("the latest known state is kept").isAfterOrEqualTo(base);

        // A suspension that happens after the resume is a real one.
        webhook(subscriptionEvent("BILLING.SUBSCRIPTION.SUSPENDED", subscriptionId, "SUSPENDED", Instant.now().plus(Duration.ofHours(1))));
        settle();
        assertThat(statusOf(member, created)).isEqualTo("PAUSED");
    }

    @Test
    void reportsThatArriveOutOfOrderAreAppliedByWhenTheyHappenedNotWhenTheyArrived() throws Exception {
        UUID member = newMember();
        Instant base = Instant.now();
        JsonNode created = activeGoal(member, "order-reports", base.minus(Duration.ofHours(3)));
        String subscriptionId = created.path("payPalSubscriptionId").asText();

        // The reactivation (the later fact) arrives first, then the suspension it overtook.
        webhook(subscriptionEvent("BILLING.SUBSCRIPTION.RE-ACTIVATED", subscriptionId, "ACTIVE", base.minus(Duration.ofHours(1))));
        settle();
        webhook(subscriptionEvent("BILLING.SUBSCRIPTION.SUSPENDED", subscriptionId, "SUSPENDED", base.minus(Duration.ofHours(2))));
        settle();
        assertThat(statusOf(member, created)).isEqualTo("ACTIVE");

        // A suspension at the very same instant as the latest state is not old news.
        webhook(subscriptionEvent("BILLING.SUBSCRIPTION.SUSPENDED", subscriptionId, "SUSPENDED", base.minus(Duration.ofHours(1))));
        settle();
        assertThat(statusOf(member, created)).isEqualTo("PAUSED");
    }

    @Test
    void aCancelledSubscriptionNeverComesBackSoItsReportIsAppliedWhateverItsTime() throws Exception {
        UUID member = newMember();
        Instant base = Instant.now();
        JsonNode created = activeGoal(member, "order-cancel", base.minus(Duration.ofHours(3)));
        String subscriptionId = created.path("payPalSubscriptionId").asText();
        webhook(subscriptionEvent("BILLING.SUBSCRIPTION.RE-ACTIVATED", subscriptionId, "ACTIVE", base));
        settle();

        webhook(subscriptionEvent("BILLING.SUBSCRIPTION.CANCELLED", subscriptionId, "CANCELLED", base.minus(Duration.ofHours(1))));
        settle();

        assertThat(statusOf(member, created)).isEqualTo("PAUSED");
    }
}
