package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.CapturedOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CheckoutOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.FundingSource;
import com.paypalseed.payments.api.dto.PaymentsDtos.PaypalCatalog;
import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsSubscriptionView;
import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsTopUp;
import com.paypalseed.payments.api.dto.PaymentsDtos.StartSavingsSubscription;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsTopUpCaptured;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/**
 * The PayPal billing catalog (product and plans created once), the savings subscription built on the savings
 * plan, and the one-off PayPal or Venmo top-up. The catalog tests come first because later tests rely on
 * the product and plans existing.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import({IntegrationTest.Fakes.class, PaypalCatalogIT.Recording.class})
class PaypalCatalogIT extends IntegrationTest {

    private static final String BOOTSTRAP = "/internal/paypal/catalog/bootstrap";

    public static class TopUpLog {
        final List<SavingsTopUpCaptured> captured = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(SavingsTopUpCaptured e) {
            captured.add(e);
        }

        List<SavingsTopUpCaptured> of(UUID goalId) {
            return captured.stream().filter(e -> e.goalId().equals(goalId)).toList();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {
        @Bean
        TopUpLog topUpLog() {
            return new TopUpLog();
        }
    }

    @Autowired
    PaymentsFacade payments;
    @Autowired
    TopUpLog topUps;
    @Autowired
    ObjectMapper json;

    private JsonNode bootstrap() throws Exception {
        String body = mvc.perform(post(BOOTSTRAP).header("Authorization", SERVICE_TOKEN)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String inboxStatus(String eventId) {
        return jdbc.sql("SELECT status FROM payments.webhook_inbox WHERE paypal_event_id = ?").param(eventId)
                .query(String.class).optional().orElse("ABSENT");
    }

    // ---- catalog ----

    @Test
    @Order(0)
    void anUnavailablePayPalIsReportedAs503AndNothingIsCreated() throws Exception {
        paypal.failNext("listProducts", FakePayPal.unavailable());

        mvc.perform(post(BOOTSTRAP).header("Authorization", SERVICE_TOKEN)).andExpect(status().isServiceUnavailable());

        assertThat(paypal.products).isEmpty();
        assertThat(paypal.plans).isEmpty();
    }

    @Test
    @Order(1)
    void bootstrapTwiceCreatesTheProductAndEachPlanOnce() throws Exception {
        JsonNode first = bootstrap();

        assertThat(first.path("createdProduct").asBoolean()).isTrue();
        assertThat(first.path("createdSavingsPlan").asBoolean()).isTrue();
        assertThat(first.path("createdLoanPlan").asBoolean()).isTrue();
        assertThat(paypal.products).hasSize(1);
        assertThat(paypal.plans).hasSize(2);
        String productId = first.path("productId").asText();
        assertThat(productId).isEqualTo(paypal.products.keySet().iterator().next());
        assertThat(paypal.plans.values()).extracting("name").containsExactlyInAnyOrder("seed-savings-autodeposit-usd-1",
                "seed-loan-autopay-usd-1");
        assertThat(paypal.plans.values()).allSatisfy(p -> {
            assertThat(p.productId()).isEqualTo(productId);
            assertThat(p.status()).isEqualTo("ACTIVE");
            assertThat(paypal.planUnitPriceCents.get(p.id())).isEqualTo(100L); // $1 per unit; quantity is whole dollars
        });
        assertThat(paypal.plans).containsKeys(first.path("savingsPlanId").asText(), first.path("loanPlanId").asText());
        int callsAfterFirst = paypal.calls.size();

        JsonNode second = bootstrap();

        assertThat(second.path("productId").asText()).isEqualTo(productId);
        assertThat(second.path("savingsPlanId").asText()).isEqualTo(first.path("savingsPlanId").asText());
        assertThat(second.path("loanPlanId").asText()).isEqualTo(first.path("loanPlanId").asText());
        assertThat(second.path("createdProduct").asBoolean()).isFalse();
        assertThat(second.path("createdSavingsPlan").asBoolean()).isFalse();
        assertThat(second.path("createdLoanPlan").asBoolean()).isFalse();
        assertThat(paypal.products).hasSize(1);
        assertThat(paypal.plans).hasSize(2);
        assertThat(paypal.callCount("createProduct")).isEqualTo(1);
        assertThat(paypal.callCount("createPlan")).isEqualTo(2);
        assertThat(paypal.calls).as("the second run is answered from what Seed recorded").hasSize(callsAfterFirst);
    }

    @Test
    @Order(2)
    void aLostLocalRecordIsRecoveredByNameWithoutCreatingAnything() {
        PaypalCatalog before = payments.ensureSavingsPlan();
        jdbc.sql("DELETE FROM payments.paypal_catalog_entry").update();

        PaypalCatalog after = payments.ensureSavingsPlan();

        assertThat(after).isEqualTo(before);
        assertThat(paypal.products).hasSize(1);
        assertThat(paypal.plans).hasSize(2);
        assertThat(paypal.callCount("createProduct")).isZero();
        assertThat(paypal.callCount("createPlan")).isZero();
        assertThat(paypal.callCount("listProducts")).isEqualTo(1);
    }

    @Test
    @Order(3)
    void onlyTheServiceMayBootstrap() throws Exception {
        mvc.perform(post(BOOTSTRAP)).andExpect(status().is4xxClientError());
        mvc.perform(post(BOOTSTRAP).header("X-Seed-Member-Id", UUID.randomUUID().toString())).andExpect(status().isForbidden());
        assertThat(paypal.calls).isEmpty();
    }

    // ---- savings subscription ----

    @Test
    @Order(4)
    void aSavingsSubscriptionIsCreatedOnTheSavingsPlanWithTheGoalInCustomId() {
        UUID member = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        StartSavingsSubscription command = new StartSavingsSubscription(member, goal, 2_500, "req-1", "https://seed.test/ok",
                "https://seed.test/no");

        SavingsSubscriptionView view = payments.createSavingsSubscription(command);

        assertThat(view.goalId()).isEqualTo(goal);
        assertThat(view.status()).isEqualTo("APPROVAL_PENDING");
        assertThat(view.approveUrl()).isPresent();
        assertThat(view.monthlyCents()).isEqualTo(2_500);
        FakePayPal.Sub sub = paypal.subscriptions.get(view.subscriptionId());
        assertThat(sub.quantity).isEqualTo(25); // $1 plan, so quantity is the monthly dollars
        assertThat(sub.customId).isEqualTo("goal:" + goal);
        assertThat(sub.planId).isEqualTo(payments.ensureSavingsPlan().savingsPlanId());
        assertThat(sub.nextBilling).isNull(); // starts when the member approves
    }

    @Test
    @Order(5)
    void replayingTheSameRequestReturnsTheSameSubscriptionAndAConflictingBodyIsA409() {
        UUID member = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        StartSavingsSubscription command = new StartSavingsSubscription(member, goal, 1_000, "req-A", "https://seed.test/ok",
                "https://seed.test/no");

        SavingsSubscriptionView first = payments.createSavingsSubscription(command);
        SavingsSubscriptionView replay = payments.createSavingsSubscription(command);

        assertThat(replay).isEqualTo(first);
        assertThat(paypal.subscriptions.values().stream().filter(s -> ("goal:" + goal).equals(s.customId)).count()).isEqualTo(1);
        assertThat(paypal.callCount("subscribeOnPlan")).isEqualTo(1);
        assertThatThrownBy(() -> payments.createSavingsSubscription(
                new StartSavingsSubscription(member, goal, 2_000, "req-A", "https://seed.test/ok", "https://seed.test/no")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.IDEMPOTENCY_KEY_REUSED));
        assertThatThrownBy(() -> payments.createSavingsSubscription(
                new StartSavingsSubscription(UUID.randomUUID(), goal, 1_000, "req-A", "https://seed.test/ok", "https://seed.test/no")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.IDEMPOTENCY_KEY_REUSED));

        SavingsSubscriptionView another = payments.createSavingsSubscription(
                new StartSavingsSubscription(member, goal, 1_000, "req-B", "https://seed.test/ok", "https://seed.test/no"));
        assertThat(another.subscriptionId()).isNotEqualTo(first.subscriptionId());
    }

    @Test
    @Order(6)
    void aSavingsSubscriptionNeedsWholeDollarsAndPayPalFailuresAreReported() {
        UUID goal = UUID.randomUUID();
        assertThatThrownBy(() -> new StartSavingsSubscription(UUID.randomUUID(), goal, 1_050, "r", "u", "c"))
                .isInstanceOf(IllegalArgumentException.class);

        paypal.failNext("subscribeOnPlan", FakePayPal.unavailable());
        StartSavingsSubscription command = new StartSavingsSubscription(UUID.randomUUID(), goal, 1_000, "req-F",
                "https://seed.test/ok", "https://seed.test/no");
        assertThatThrownBy(() -> payments.createSavingsSubscription(command)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.DEPENDENCY_UNAVAILABLE));
        assertThat(payments.createSavingsSubscription(command).subscriptionId()).isNotBlank();

        paypal.failNext("subscribeOnPlan", FakePayPal.refused("PLAN_NOT_ACTIVE"));
        StartSavingsSubscription refused = new StartSavingsSubscription(UUID.randomUUID(), UUID.randomUUID(), 1_000, "req-R",
                "https://seed.test/ok", "https://seed.test/no");
        assertThatThrownBy(() -> payments.createSavingsSubscription(refused)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PaymentsErrors.PAYPAL_REJECTED));
    }

    @Test
    @Order(7)
    void aSavingsSubscriptionsWebhooksNeverReachTheLoanFunnel() throws Exception {
        UUID goal = UUID.randomUUID();
        String subscriptionId = payments.createSavingsSubscription(new StartSavingsSubscription(UUID.randomUUID(), goal, 2_000,
                "req-W", "https://seed.test/ok", "https://seed.test/no")).subscriptionId();
        String activated = PayPalWebhooks.newEventId();
        String sale = PayPalWebhooks.newEventId();
        String failed = PayPalWebhooks.newEventId();

        webhook(PayPalWebhooks.subscription(activated, "BILLING.SUBSCRIPTION.ACTIVATED", subscriptionId, "ACTIVE", 20, Instant.now()));
        String saleTxn = "SALE-" + UUID.randomUUID();
        webhook(PayPalWebhooks.saleCompleted(sale, subscriptionId, saleTxn, 2_000, 0));
        webhook(PayPalWebhooks.paymentFailed(failed, subscriptionId));
        settle();

        assertThat(inboxStatus(activated)).isEqualTo("PROCESSED");
        // A sale on a savings subscription is handled as a savings deposit (announced to the savings module, which
        // drops it here because this goal was never created); it is never recorded as a loan payment.
        assertThat(inboxStatus(sale)).isEqualTo("PROCESSED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payments.captured_payment WHERE paypal_txn_id = ?").param(saleTxn)
                .query(Integer.class).single()).isZero();
        assertThat(inboxStatus(failed)).isEqualTo("IGNORED");
        assertThat(jdbc.sql("SELECT status FROM payments.savings_subscription WHERE paypal_subscription_id = ?")
                .param(subscriptionId).query(String.class).single()).isEqualTo("ACTIVE");
    }

    // ---- one-off top-up (the Venmo route) ----

    private String captureCompleted(String eventId, String captureId, UUID goal, String orderId, long cents) {
        return """
                {"id":"%s","event_version":"1.0","create_time":"%s","event_type":"PAYMENT.CAPTURE.COMPLETED","resource":
                 {"id":"%s","status":"COMPLETED","custom_id":"goal:%s:TOPUP","amount":{"value":"%s","currency_code":"USD"},
                  "supplementary_data":{"related_ids":{"order_id":"%s"}},"create_time":"%s"}}
                """.formatted(eventId, Instant.now(), captureId, goal, Money.ofCents(cents).toDecimalString(), orderId,
                Instant.now());
    }

    @Test
    @Order(8)
    void aVenmoTopUpIsAnOrderFundedByVenmoAndCapturedOnce() throws Exception {
        UUID member = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        SavingsTopUp command = new SavingsTopUp(member, goal, 1_500, FundingSource.VENMO, "topup-1");

        CheckoutOrder order = payments.createSavingsTopUp(command);

        assertThat(paypal.orderFunding.get(order.orderId())).isEqualTo(FundingSource.VENMO);
        assertThat(order.amountCents()).isEqualTo(1_500);
        assertThat(order.approveUrl()).isPresent();
        assertThat(payments.createSavingsTopUp(command).orderId()).isEqualTo(order.orderId());
        assertThat(paypal.callCount("createOrder")).isEqualTo(1);
        assertThatThrownBy(() -> payments.createSavingsTopUp(new SavingsTopUp(member, goal, 1_600, FundingSource.VENMO, "topup-1")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.IDEMPOTENCY_KEY_REUSED));

        CapturedOrder captured = payments.captureSavingsTopUp(goal, order.orderId());

        assertThat(captured.status()).isEqualTo("COMPLETED");
        assertThat(captured.amountCents()).isEqualTo(1_500);
        assertThat(topUps.of(goal)).singleElement().satisfies(e -> {
            assertThat(e.memberId()).isEqualTo(member);
            assertThat(e.funding()).isEqualTo(FundingSource.VENMO);
            assertThat(e.amountCents()).isEqualTo(1_500);
            assertThat(e.paypalCaptureId()).isEqualTo(captured.paypalTxnId());
        });
        assertThat(payments.captureSavingsTopUp(goal, order.orderId())).isEqualTo(captured);
        assertThat(paypal.callCount("captureOrder")).isEqualTo(1);

        webhook(captureCompleted(PayPalWebhooks.newEventId(), captured.paypalTxnId(), goal, order.orderId(), 1_500));
        String refunded = PayPalWebhooks.newEventId();
        webhook(PayPalWebhooks.captureRefunded(refunded, "REF-" + UUID.randomUUID(), captured.paypalTxnId(), 500));
        settle();

        assertThat(topUps.of(goal)).hasSize(1);
        assertThat(inboxStatus(refunded)).isEqualTo("IGNORED");
    }

    @Test
    @Order(9)
    void aTopUpCapturedByWebhookFirstIsNotAnnouncedAgainWhenTheApiCaptureFollows() throws Exception {
        UUID goal = UUID.randomUUID();
        CheckoutOrder order = payments.createSavingsTopUp(new SavingsTopUp(UUID.randomUUID(), goal, 800, FundingSource.PAYPAL, "k-2"));
        assertThat(paypal.orderFunding.get(order.orderId())).isEqualTo(FundingSource.PAYPAL);

        webhook(captureCompleted(PayPalWebhooks.newEventId(), "CAP-WEBHOOK-" + goal, goal, order.orderId(), 800));
        settle();
        assertThat(topUps.of(goal)).hasSize(1);

        payments.captureSavingsTopUp(goal, order.orderId());
        settle();

        assertThat(topUps.of(goal)).hasSize(1);
    }

    @Test
    @Order(10)
    void aTopUpCannotBeCapturedThroughAnotherGoal() {
        UUID goal = UUID.randomUUID();
        CheckoutOrder order = payments.createSavingsTopUp(new SavingsTopUp(UUID.randomUUID(), goal, 800, FundingSource.PAYPAL, "k-3"));

        assertThatThrownBy(() -> payments.captureSavingsTopUp(UUID.randomUUID(), order.orderId()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(PaymentsErrors.TOPUP_NOT_FOUND));
        assertThatThrownBy(() -> payments.captureSavingsTopUp(goal, "ORDER-UNKNOWN"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(PaymentsErrors.TOPUP_NOT_FOUND));
        assertThat(paypal.callCount("captureOrder")).isZero();
    }
}
