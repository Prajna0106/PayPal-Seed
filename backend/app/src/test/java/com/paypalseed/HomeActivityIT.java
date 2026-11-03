package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.ReserveCover;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.FakePayPal.SimPayout;
import com.paypalseed.support.PayPalWebhooks;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the member Home page and the savings routes gained in the closing pass (ADR 0027), over real PostgreSQL: recent
 * activity from the read model, the next deposit date taken from PayPal's reports, the deposits of a goal, and the amount
 * change route's limits. Each section still fails alone; that is pinned in {@code HomeActivityTest}.
 */
class HomeActivityIT extends MoneyFlowSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Instant FIRST_BILLING = Instant.parse("2026-12-03T17:00:00Z");
    private static final Instant SECOND_BILLING = Instant.parse("2027-01-03T17:00:00Z");

    @Autowired
    RepaymentService repayments;

    @BeforeEach
    void today() {
        clock.set(TODAY);
    }

    private JsonNode home(UUID member) throws Exception {
        return read(mvc.perform(asMember(get("/api/v1/members/me/home"), member)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode createGoal(UUID member, String key) throws Exception {
        return read(mvc.perform(asMember(post("/api/v1/savings/goals"), member).header("Idempotency-Key", key)
            .content("{\"name\":\"Mixer\",\"targetAmount\":500.00,\"monthlyAmount\":25,\"wallet\":\"PAYPAL\"}"))
            .andExpect(status().isCreated()).andReturn());
    }

    /** A goal the member approved at PayPal; PayPal says it bills next on {@link #FIRST_BILLING}. */
    private JsonNode approvedGoal(UUID member, String key) throws Exception {
        JsonNode created = createGoal(member, key);
        String subscriptionId = created.path("payPalSubscriptionId").asText();
        paypal.approve(subscriptionId);
        paypal.subscriptions.get(subscriptionId).nextBilling = FIRST_BILLING;
        webhook(PayPalWebhooks.subscription(PayPalWebhooks.newEventId(), "BILLING.SUBSCRIPTION.ACTIVATED", subscriptionId, "ACTIVE", 25,
            FIRST_BILLING));
        settle();
        return goal(member, created.path("id").asText());
    }

    private static JsonNode firstGoalOf(JsonNode home) {
        return home.path("savings").path("goals").get(0);
    }

    /** A field the page leaves out when it has no value reads as missing, not as null. */
    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static List<String> kinds(JsonNode activity) {
        List<String> kinds = new ArrayList<>();
        activity.forEach(a -> kinds.add(a.path("kind").asText()));
        return kinds;
    }

    // ---- savings: next deposit date, deposits, activity ----

    @Test
    void theNextDepositDateComesFromPayPalsReportsAndFollowsThePlanThroughPauseAndResume() throws Exception {
        UUID member = newMember();
        JsonNode active = approvedGoal(member, "next-date");
        String goalId = active.path("id").asText();
        String subscriptionId = active.path("payPalSubscriptionId").asText();

        JsonNode first = home(member);
        assertThat(first.path("sectionErrors")).isEmpty();
        assertThat(firstGoalOf(first).path("nextDepositDate").asText()).as("the activation report").isEqualTo("2026-12-03");

        // A deposit sale moves PayPal's next charge on, and the sale report does not say where: Seed asks PayPal.
        paypal.subscriptions.get(subscriptionId).nextBilling = SECOND_BILLING;
        webhook(PayPalWebhooks.saleCompleted(PayPalWebhooks.newEventId(), subscriptionId, "SALE-DATE-1", 2_500, 90));
        settle();
        assertThat(firstGoalOf(home(member)).path("nextDepositDate").asText()).isEqualTo("2027-01-03");

        // Paused: nothing is billed, so nothing is promised, here and in the table.
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/pause", goalId), member)).andExpect(status().isOk());
        assertThat(absent(firstGoalOf(home(member)).path("nextDepositDate"))).isTrue();
        assertThat(jdbc.sql("SELECT next_billing_time IS NULL FROM payments.savings_subscription WHERE paypal_subscription_id = ?")
            .param(subscriptionId).query(Boolean.class).single()).isTrue();

        // Resumed: the goal is active again, but no date until PayPal reports one.
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/resume", goalId), member)).andExpect(status().isOk());
        assertThat(absent(firstGoalOf(home(member)).path("nextDepositDate"))).isTrue();
        webhook(PayPalWebhooks.subscription(PayPalWebhooks.newEventId(), "BILLING.SUBSCRIPTION.RE-ACTIVATED", subscriptionId, "ACTIVE",
            25, Instant.parse("2027-02-03T17:00:00Z")));
        settle();
        assertThat(firstGoalOf(home(member)).path("nextDepositDate").asText()).isEqualTo("2027-02-03");
    }

    @Test
    void aTimeThatHasAlreadyPassedIsNotShown() throws Exception {
        UUID member = newMember();
        approvedGoal(member, "stale-date");
        assertThat(firstGoalOf(home(member)).path("nextDepositDate").asText()).isEqualTo("2026-12-03");

        clock.set(LocalDate.of(2026, 12, 4));

        assertThat(absent(firstGoalOf(home(member)).path("nextDepositDate"))).isTrue();
    }

    @Test
    void theDepositsOfAGoalComeNewestFirstAndOnlyTheOwnerSeesThem() throws Exception {
        UUID member = newMember();
        JsonNode active = approvedGoal(member, "deposits");
        String goalId = active.path("id").asText();
        String subscriptionId = active.path("payPalSubscriptionId").asText();
        assertThat(read(mvc.perform(asMember(get("/api/v1/savings/goals/{id}/deposits", goalId), member)).andExpect(status().isOk()).andReturn()))
            .as("none yet").isEmpty();

        webhook(PayPalWebhooks.saleCompleted(PayPalWebhooks.newEventId(), subscriptionId, "SALE-D-1", 2_500, 90));
        settle();
        // a Venmo/PayPal one-off top-up is a deposit too
        String orderId = read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/topup", goalId), member).header("Idempotency-Key", "d-topup")
            .content("{\"amount\":10.50}")).andExpect(status().isCreated()).andReturn()).path("orderId").asText();
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/topup/{order}/capture", goalId, orderId), member)).andExpect(status().isOk());
        settle();

        JsonNode deposits = read(mvc.perform(asMember(get("/api/v1/savings/goals/{id}/deposits", goalId), member))
            .andExpect(status().isOk()).andReturn());
        assertThat(deposits).hasSize(2);
        assertThat(deposits.get(0).path("source").asText()).isEqualTo("TOPUP");
        assertThat(deposits.get(0).path("amountCents").asLong()).isEqualTo(1_050);
        assertThat(deposits.get(0).path("amount").decimalValue()).isEqualByComparingTo("10.50");
        assertThat(deposits.get(1).path("source").asText()).isEqualTo("SUBSCRIPTION");
        assertThat(deposits.get(1).path("amountCents").asLong()).isEqualTo(2_500);
        assertThat(deposits.get(1).path("currency").asText()).isEqualTo("USD");
        assertThat(Instant.parse(deposits.get(0).path("creditedAt").asText())).isAfterOrEqualTo(Instant.parse(deposits.get(1).path("creditedAt").asText()));
        assertThat(deposits.toString()).as("no PayPal reference").doesNotContain("SALE-D-1");

        mvc.perform(asMember(get("/api/v1/savings/goals/{id}/deposits", goalId).param("limit", "1"), member)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].source").value("TOPUP"));
        mvc.perform(asMember(get("/api/v1/savings/goals/{id}/deposits", goalId).param("limit", "0"), member)).andExpect(status().isBadRequest());
        mvc.perform(asMember(get("/api/v1/savings/goals/{id}/deposits", goalId).param("limit", "201"), member)).andExpect(status().isBadRequest());

        UUID stranger = newMember();
        mvc.perform(asMember(get("/api/v1/savings/goals/{id}/deposits", goalId), stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/savings/goals/{id}/deposits", goalId)).andExpect(status().isUnauthorized());
        mvc.perform(asStaff(get("/api/v1/savings/goals/{id}/deposits", goalId), UUID.randomUUID(), "OFFICER")).andExpect(status().isNotFound());
        mvc.perform(asMember(get("/api/v1/savings/goals/{id}/deposits", UUID.randomUUID()), member)).andExpect(status().isNotFound());
    }

    @Test
    void recentActivityListsDepositsAndWithdrawalsNewestFirstAndOnlyTheMembersOwn() throws Exception {
        UUID member = newMember();
        JsonNode active = approvedGoal(member, "activity");
        String goalId = active.path("id").asText();
        String subscriptionId = active.path("payPalSubscriptionId").asText();
        assertThat(home(member).path("recentActivity")).as("none yet, and not an error").isEmpty();

        webhook(PayPalWebhooks.saleCompleted(PayPalWebhooks.newEventId(), subscriptionId, "SALE-A-1", 5_000, 90));
        settle();
        JsonNode asked = read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member).header("Idempotency-Key", "a-wd")
            .content("{\"amount\":5}")).andExpect(status().isAccepted()).andReturn());
        SimPayout payout = paypal.payoutOfReference(asked.path("id").asText());
        payoutSucceededWebhook(payout);
        settle();

        JsonNode page = home(member);
        assertThat(page.path("sectionErrors")).isEmpty();
        JsonNode activity = page.path("recentActivity");
        assertThat(kinds(activity)).containsExactly("WITHDRAWAL", "DEPOSIT");
        assertThat(activity.get(0).path("amount").path("cents").asLong()).isEqualTo(500);
        assertThat(activity.get(0).path("amount").path("currency").asText()).isEqualTo("USD");
        assertThat(activity.get(0).path("goalId").asText()).isEqualTo(goalId);
        assertThat(activity.get(1).path("amount").path("cents").asLong()).isEqualTo(5_000);
        assertThat(Instant.parse(activity.get(0).path("occurredAt").asText())).isAfterOrEqualTo(Instant.parse(activity.get(1).path("occurredAt").asText()));
        assertThat(activity.toString()).as("no PayPal reference and nothing about hardship").doesNotContain("SALE-A-1")
            .doesNotContain("hardship").doesNotContain("PAYOUT");

        // somebody else sees none of it
        assertThat(home(newMember()).path("recentActivity")).isEmpty();
        mvc.perform(get("/api/v1/members/me/home")).andExpect(status().isUnauthorized());
    }

    @Test
    void theAmountChangeIsOnlyForTheOwnersGoalOnceItIsApproved() throws Exception {
        UUID owner = newMember();
        JsonNode pending = createGoal(owner, "amount-pending");
        String pendingId = pending.path("id").asText();
        JsonNode active = approvedGoal(owner, "amount-active");
        String activeId = active.path("id").asText();

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/amount", activeId), newMember()).content("{\"monthlyAmount\":40}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/savings/goals/{id}/amount", activeId).contentType("application/json").content("{\"monthlyAmount\":40}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/amount", pendingId), owner).content("{\"monthlyAmount\":40}"))
            .andExpect(status().isConflict());

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/amount", activeId), owner).content("{\"monthlyAmount\":40}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.monthlyAmount").value(40));
        JsonNode changed = null;
        for (JsonNode g : home(owner).path("savings").path("goals")) {
            if (g.path("goalId").asText().equals(activeId)) {
                changed = g;
            }
        }
        assertThat(changed).isNotNull();
        assertThat(changed.path("monthly").path("cents").asLong()).isEqualTo(4_000);
        settle();
    }

    // ---- loans: payout, payment, cover ----

    @Test
    void theLoanPayoutAPaymentAndAPaymentTheFundMadeAreInTheActivity() throws Exception {
        UUID member = UUID.randomUUID();
        LoanSummary loan = disburse(member, 1_200, 8, 20, TODAY);
        settle();
        repayments.recordReceipt(new RepaymentService.Receipt(loan.loanId(), "PAY-ACT-1", Source.CHECKOUT, 5_000, 0, Instant.now(), null, "it"));
        lending.applyReserveCover(new ReserveCover(loan.loanId(), 8_000, "reserve-cover:act-" + loan.loanId(), UUID.randomUUID(),
            clock.today(), clock.now()));
        settle();

        JsonNode activity = home(member).path("recentActivity");

        assertThat(kinds(activity)).containsExactlyInAnyOrder("LOAN_DISBURSED", "LOAN_PAYMENT", "COVER_APPLIED");
        for (JsonNode item : activity) {
            assertThat(item.path("loanId").asText()).isEqualTo(loan.loanId().toString());
            assertThat(item.path("goalId").isMissingNode() || item.path("goalId").isNull()).isTrue();
            long cents = item.path("amount").path("cents").asLong();
            switch (item.path("kind").asText()) {
                case "LOAN_DISBURSED" -> assertThat(cents).isEqualTo(120_000);
                case "LOAN_PAYMENT" -> assertThat(cents).isEqualTo(5_000);
                default -> assertThat(cents).isEqualTo(8_000);
            }
            assertThat(Instant.parse(item.path("occurredAt").asText()).atZone(BusinessClock.PROGRAM_ZONE).toLocalTime())
                .as("a date-only source is dated at the start of its business day").isEqualTo(java.time.LocalTime.MIDNIGHT);
        }
        assertThat(home(UUID.randomUUID()).path("recentActivity")).isEmpty();
    }
}
