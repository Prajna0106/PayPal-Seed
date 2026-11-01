package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.savings.application.ports.CreditedPayments.Source;
import com.paypalseed.savings.application.usecases.CreditPaymentUseCase;
import com.paypalseed.savings.application.usecases.ExpireApprovalsUseCase;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.payments.api.events.PaymentEvents.DonationCaptured;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/**
 * Savings through PayPal subscriptions and Venmo top-ups, over HTTP and webhooks: the backend creates the
 * subscription, the goal waits for PayPal to say the member approved, deposits credit once, and PayPal-side
 * changes pause the goal.
 */
@Import({IntegrationTest.Fakes.class, SavingsSubscriptionIT.Recording.class})
class SavingsSubscriptionIT extends MoneyFlowSupport {

    /** Nobody listens for donations yet (the reserve module adds its listener), so the test listens itself. */
    public static class Donations {
        final List<DonationCaptured> captured = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(DonationCaptured e) {
            captured.add(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {
        @Bean
        Donations donations() {
            return new Donations();
        }
    }

    @Autowired
    Donations donations;

    @Autowired
    CreditPaymentUseCase credit;
    @Autowired
    ExpireApprovalsUseCase expiry;

    private JsonNode createGoal(UUID member, String key, double monthly, String wallet) throws Exception {
        return read(mvc.perform(asMember(post("/api/v1/savings/goals"), member).header("Idempotency-Key", key).content("""
                {"name":"Mixer","targetAmount":500.00,"monthlyAmount":%s,"wallet":"%s"}
                """.formatted(monthly, wallet))).andExpect(status().isCreated()).andReturn());
    }

    private FakePayPal.Sub sub(String subscriptionId) {
        return paypal.subscriptions.get(subscriptionId);
    }

    private JsonNode approvedGoal(UUID member, String key) throws Exception {
        JsonNode created = createGoal(member, key, 25, "PAYPAL");
        String subscriptionId = created.path("payPalSubscriptionId").asText();
        paypal.approve(subscriptionId);
        subscriptionWebhook("BILLING.SUBSCRIPTION.ACTIVATED", subscriptionId, "ACTIVE");
        settle();
        return goal(member, created.path("id").asText());
    }

    @Test
    void creatingAGoalReturnsTheApprovalUrlAndWaitsForApproval() throws Exception {
        UUID member = newMember();

        JsonNode goal = createGoal(member, "create-1", 25, "PAYPAL");

        assertThat(goal.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(goal.path("approvalUrl").asText()).startsWith("https://sandbox.paypal.test/approve/");
        assertThat(goal.path("approvalExpiresAt").asText()).isNotBlank();
        assertThat(goal.path("wallet").asText()).isEqualTo("PAYPAL");
        FakePayPal.Sub created = sub(goal.path("payPalSubscriptionId").asText());
        assertThat(created.quantity).isEqualTo(25);
        assertThat(created.customId).isEqualTo("goal:" + goal.path("id").asText());
        assertThat(created.status).isEqualTo("APPROVAL_PENDING");
        settle();
    }

    @Test
    void theSameKeyReturnsTheSameGoalAndOnlyOneSubscriptionExists() throws Exception {
        UUID member = newMember();
        JsonNode first = createGoal(member, "create-replay", 25, "PAYPAL");
        long subscriptionsBefore = paypal.subscriptions.size();

        JsonNode replay = read(mvc.perform(asMember(post("/api/v1/savings/goals"), member).header("Idempotency-Key", "create-replay")
            .content("{\"name\":\"Mixer\",\"targetAmount\":500.00,\"monthlyAmount\":25,\"wallet\":\"PAYPAL\"}"))
            .andExpect(status().isOk()).andReturn());

        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(replay.path("payPalSubscriptionId").asText()).isEqualTo(first.path("payPalSubscriptionId").asText());
        assertThat(paypal.subscriptions.size()).isEqualTo(subscriptionsBefore);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM savings.savings_goal WHERE member_id = ?").param(member.toString())
            .query(Integer.class).single()).isEqualTo(1);
        settle();
    }

    @Test
    void theSameKeyForADifferentGoalIs409AndAKeyIsRequired() throws Exception {
        UUID member = newMember();
        createGoal(member, "create-conflict", 25, "PAYPAL");

        mvc.perform(asMember(post("/api/v1/savings/goals"), member).header("Idempotency-Key", "create-conflict")
                .content("{\"name\":\"Mixer\",\"targetAmount\":500.00,\"monthlyAmount\":30,\"wallet\":\"PAYPAL\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("idempotency-key-reused")));
        mvc.perform(asMember(post("/api/v1/savings/goals"), member)
                .content("{\"name\":\"Oven\",\"targetAmount\":500.00,\"monthlyAmount\":30}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("idempotency-key-required")));
        mvc.perform(asMember(post("/api/v1/savings/goals"), member).header("Idempotency-Key", "cents")
                .content("{\"name\":\"Oven\",\"targetAmount\":500.00,\"monthlyAmount\":30.50}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("amount-not-whole-dollars")));
        settle();
    }

    @Test
    void depositsAreRejectedUntilPayPalReportsTheActivation() throws Exception {
        UUID member = newMember();
        JsonNode goal = createGoal(member, "before-activation", 25, "PAYPAL");
        UUID goalId = UUID.fromString(goal.path("id").asText());

        assertThatThrownBy(() -> credit.credit(goalId, "SALE-EARLY", 2_500, Source.SUBSCRIPTION))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("pending");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM savings.credited_payment WHERE paypal_txn_id = ?").param("SALE-EARLY")
            .query(Integer.class).single()).as("the claim rolled back with the refusal").isZero();
        assertThat(goal(member, goal.path("id").asText()).path("currentBalance").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void theActivationWebhookActivatesTheGoalAndDepositsCreditOnce() throws Exception {
        UUID member = newMember();
        JsonNode active = approvedGoal(member, "activation");
        String goalId = active.path("id").asText();
        String subscriptionId = active.path("payPalSubscriptionId").asText();

        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(active.path("approvalUrl").isMissingNode() || active.path("approvalUrl").isNull()).isTrue();

        String saleEvent = newEventId();
        saleWebhook(saleEvent, subscriptionId, "SALE-1", 2_500);
        settle();
        assertThat(goal(member, goalId).path("currentBalance").decimalValue()).isEqualByComparingTo("25");
        assertThat(memberSavingsInBooks(member)).isEqualTo(2_500);

        // the same delivery again, and the same sale under another webhook id: still one deposit
        saleWebhook(saleEvent, subscriptionId, "SALE-1", 2_500);
        saleWebhook(newEventId(), subscriptionId, "SALE-1", 2_500);
        settle();
        assertThat(goal(member, goalId).path("currentBalance").decimalValue()).isEqualByComparingTo("25");
        assertThat(memberSavingsInBooks(member)).isEqualTo(2_500);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM savings.credited_payment WHERE goal_id = ?").param(UUID.fromString(goalId))
            .query(Integer.class).single()).isEqualTo(1);

        // the next month's sale is a new deposit
        saleWebhook(newEventId(), subscriptionId, "SALE-2", 2_500);
        settle();
        assertThat(goal(member, goalId).path("currentBalance").decimalValue()).isEqualByComparingTo("50");
        assertLedgerBalanced();
    }

    @Test
    void cancellingOrSuspendingInPayPalPausesTheGoal() throws Exception {
        UUID member = newMember();
        JsonNode first = approvedGoal(member, "cancel-in-paypal");
        subscriptionWebhook("BILLING.SUBSCRIPTION.CANCELLED", first.path("payPalSubscriptionId").asText(), "CANCELLED");
        settle();
        assertThat(goal(member, first.path("id").asText()).path("status").asText()).isEqualTo("PAUSED");

        JsonNode second = approvedGoal(member, "suspend-in-paypal");
        subscriptionWebhook("BILLING.SUBSCRIPTION.SUSPENDED", second.path("payPalSubscriptionId").asText(), "SUSPENDED");
        settle();
        assertThat(goal(member, second.path("id").asText()).path("status").asText()).isEqualTo("PAUSED");
    }

    @Test
    void pausingResumingAndChangingTheAmountKeepPayPalInStep() throws Exception {
        UUID member = newMember();
        JsonNode active = approvedGoal(member, "manage");
        String goalId = active.path("id").asText();
        FakePayPal.Sub remote = sub(active.path("payPalSubscriptionId").asText());

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/pause", goalId), member)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"));
        assertThat(remote.status).isEqualTo("SUSPENDED");

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/resume", goalId), member)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(remote.status).isEqualTo("ACTIVE");

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/amount", goalId), member).content("{\"monthlyAmount\":40}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.monthlyAmount").value(40));
        assertThat(remote.quantity).isEqualTo(40);
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/amount", goalId), member).content("{\"monthlyAmount\":40.50}"))
            .andExpect(status().isUnprocessableEntity());
        settle();
    }

    @Test
    void aGoalNeverApprovedExpiresAndALateApprovalCancelsTheSubscription() throws Exception {
        UUID member = newMember();
        JsonNode goal = createGoal(member, "never-approved", 25, "PAYPAL");
        String subscriptionId = goal.path("payPalSubscriptionId").asText();
        clock.set(LocalDate.of(2026, 11, 12));

        assertThat(expiry.expireDue()).isGreaterThanOrEqualTo(1);
        assertThat(goal(member, goal.path("id").asText()).path("status").asText()).isEqualTo("EXPIRED");

        paypal.approve(subscriptionId);
        subscriptionWebhook("BILLING.SUBSCRIPTION.ACTIVATED", subscriptionId, "ACTIVE");
        settle();

        assertThat(goal(member, goal.path("id").asText()).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(sub(subscriptionId).status).as("billing stopped at PayPal").isEqualTo("CANCELLED");
        clock.set(LocalDate.of(2026, 11, 10));
    }

    @Test
    void aVenmoGoalSavesWithOneOffTopUpsThatCreditOnceWhicheverReportsFirst() throws Exception {
        UUID member = newMember();
        JsonNode goal = createGoal(member, "venmo-goal", 25.50, "VENMO");
        String goalId = goal.path("id").asText();
        assertThat(goal.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(goal.path("payPalSubscriptionId").isNull() || goal.path("payPalSubscriptionId").isMissingNode()).isTrue();

        JsonNode order = read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/topup", goalId), member)
                .header("Idempotency-Key", "topup-1").content("{\"amount\":25.00}"))
            .andExpect(status().isCreated()).andReturn());
        String orderId = order.path("orderId").asText();
        assertThat(order.path("approvalUrl").asText()).startsWith("https://sandbox.paypal.test/checkout/");
        assertThat(paypal.orderFunding.get(orderId)).isEqualTo(com.paypalseed.payments.api.dto.PaymentsDtos.FundingSource.VENMO);
        assertThat(goal(member, goalId).path("currentBalance").decimalValue()).as("nothing before PayPal confirms").isEqualByComparingTo("0");

        // the same key again is the same order
        JsonNode again = read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/topup", goalId), member)
                .header("Idempotency-Key", "topup-1").content("{\"amount\":25.00}")).andExpect(status().isCreated()).andReturn());
        assertThat(again.path("orderId").asText()).isEqualTo(orderId);

        JsonNode captured = read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/topup/{order}/capture", goalId, orderId), member))
            .andExpect(status().isOk()).andReturn());
        assertThat(captured.path("status").asText()).isEqualTo("COMPLETED");
        settle();
        assertThat(goal(member, goalId).path("currentBalance").decimalValue()).isEqualByComparingTo("25");
        assertThat(memberSavingsInBooks(member)).isEqualTo(2_500);

        // the capture webhook for the same capture arrives later: no second credit
        String captureId = jdbc.sql("SELECT paypal_capture_id FROM payments.savings_topup WHERE paypal_order_id = ?").param(orderId)
            .query(String.class).single();
        topUpCaptureWebhook(newEventId(), captureId, orderId, goalId, 2_500);
        settle();
        assertThat(goal(member, goalId).path("currentBalance").decimalValue()).isEqualByComparingTo("25");
        assertLedgerBalanced();
    }

    @Test
    void aDonationCaptureIsAnnouncedOnce() throws Exception {
        String customId = "reserve-donation:" + UUID.randomUUID();
        String eventId = newEventId();
        String capture = """
                {"id":"%s","event_version":"1.0","create_time":"%s","event_type":"PAYMENT.CAPTURE.COMPLETED","resource":
                 {"id":"DON-CAP-1","status":"COMPLETED","custom_id":"%s","amount":{"value":"20.00","currency_code":"USD"},
                  "seller_receivable_breakdown":{"paypal_fee":{"value":"0.88","currency_code":"USD"}},"create_time":"%s"}}
                """.formatted(eventId, Instant.now(), customId, Instant.now());

        webhook(capture);
        webhook(capture); // PayPal delivers the same webhook again: the inbox keeps one
        settle();

        assertThat(donations.captured).filteredOn(d -> d.customId().equals(customId)).singleElement().satisfies(d -> {
            assertThat(d.paypalTxnId()).isEqualTo("DON-CAP-1");
            assertThat(d.grossCents()).isEqualTo(2_000);
            assertThat(d.feeCents()).isEqualTo(88);
        });
    }

    @Test
    void anotherMembersGoalIs404AndAnonymousCallersAreRefused() throws Exception {
        UUID owner = newMember();
        UUID stranger = newMember();
        JsonNode goal = createGoal(owner, "private", 25, "PAYPAL");
        String id = goal.path("id").asText();

        mvc.perform(asMember(get("/api/v1/savings/goals/{id}", id), stranger)).andExpect(status().isNotFound());
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/pause", id), stranger)).andExpect(status().isNotFound());
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/topup", id), stranger).header("Idempotency-Key", "x")
            .content("{\"amount\":5}")).andExpect(status().isNotFound());
        mvc.perform(asMember(get("/api/v1/savings/members/{m}/goals", owner), stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/savings/goals/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(asStaff(get("/api/v1/savings/goals/{id}", id), UUID.randomUUID(), "OFFICER")).andExpect(status().isNotFound());
        mvc.perform(asMember(get("/api/v1/savings/members/{m}/goals", owner), owner)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(id));
        settle();
    }

    @Test
    void aClientMadeSubscriptionIdStillWorksInTestAndGivesAnActiveGoal() throws Exception {
        UUID member = newMember();

        JsonNode goal = legacyGoal(member, 25);

        assertThat(goal.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(goal.path("payPalSubscriptionId").asText()).startsWith("legacy-");
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_SAVINGS, member))).isZero();
        settle();
    }
}
