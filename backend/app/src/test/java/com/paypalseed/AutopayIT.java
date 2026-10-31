package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.application.usecases.CloseOfBusiness;
import com.paypalseed.lending.application.usecases.LoanQueries.TransactionView;
import com.paypalseed.payments.application.ports.PayPalGateway.SubscriptionTransaction;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * FR-03, FR-11, FR-13, FR-14: autopay enrolment, collection, failure and retry, the mandate
 * reconciler, and the watchdog, against a stateful PayPal simulator. Tests that close business dates
 * are ordered so the business date only moves forward.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutopayIT extends IntegrationTest {

    @Autowired
    ObjectMapper json;
    @Autowired
    CloseOfBusiness cob;
    @Autowired
    ReminderRepository reminders;

    private String startAutopay(LoanSummary loan) throws Exception {
        String body = mvc.perform(post("/api/v1/loans/{id}/autopay", loan.loanId())
                        .header("X-Seed-Member-Id", loan.memberId().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"returnUrl\":\"https://seed.test/return\",\"cancelUrl\":\"https://seed.test/cancel\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("subscriptionId").asText();
    }

    /** Enrols and approves autopay; returns the PayPal subscription id. */
    private String autopayOn(LoanSummary loan) throws Exception {
        String sub = startAutopay(loan);
        paypal.approve(sub);
        FakePayPal.Sub s = paypal.subscriptions.get(sub);
        webhook(PayPalWebhooks.subscription(PayPalWebhooks.newEventId(), "BILLING.SUBSCRIPTION.ACTIVATED", sub, "ACTIVE",
                s.quantity, s.nextBilling));
        settle();
        assertThat(queries.loan(loan.loanId()).collectionMode().name()).isEqualTo("AUTOPAY");
        return sub;
    }

    @Test
    @Order(1)
    void tcMnd01_tcMnd02_enrolmentIsIdempotentAndActivationRacesSafely() throws Exception {
        LoanSummary loan = disburse(UUID.randomUUID(), 1_200, 8, 9, LocalDate.of(2026, 11, 10));
        String first = startAutopay(loan);
        String second = startAutopay(loan);
        assertThat(second).isEqualTo(first);
        assertThat(paypal.callCount("createSubscription")).isEqualTo(1);
        FakePayPal.Sub sub = paypal.subscriptions.get(first);
        assertThat(sub.quantity).isEqualTo(150);
        assertThat(sub.customId).isEqualTo("loan:" + loan.loanId());

        paypal.approve(first);
        webhook(PayPalWebhooks.subscription(PayPalWebhooks.newEventId(), "BILLING.SUBSCRIPTION.ACTIVATED", first, "ACTIVE",
                sub.quantity, sub.nextBilling));
        mvc.perform(post("/api/v1/loans/{id}/autopay/confirm", loan.loanId())
                        .header("X-Seed-Member-Id", loan.memberId().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subscriptionId\":\"" + first + "\"}"))
                .andExpect(status().isOk());
        settle();

        assertThat(queries.loan(loan.loanId()).collectionMode().name()).isEqualTo("AUTOPAY");
        Integer activations = jdbc.sql("SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%MandateActivated'")
                .query(Integer.class).single();
        assertThat(activations).isEqualTo(1);
    }

    @Test
    @Order(2)
    void tcRty01_autopaySaleThenFailureThenOneRetryThatCollects() throws Exception {
        LoanSummary loan = disburse(UUID.randomUUID(), 1_200, 8, 9, LocalDate.of(2026, 11, 10));
        String sub = autopayOn(loan);

        clock.set(LocalDate.of(2026, 12, 9));
        webhook(PayPalWebhooks.saleCompleted(PayPalWebhooks.newEventId(), sub, "SALE-DEC", 15_000, 0));
        settle();
        assertThat(lending.currentSchedule(loan.loanId()).instalments().get(0).status()).isEqualTo("PAID");

        clock.set(LocalDate.of(2027, 1, 9));
        webhook(PayPalWebhooks.paymentFailed(PayPalWebhooks.newEventId(), sub));
        settle();
        var attempts = jdbc.sql("SELECT kind || ':' || status || ':' || planned_on FROM lending.collection_attempt "
                + "WHERE loan_id = :l ORDER BY attempt_no").param("l", loan.loanId()).query(String.class).list();
        assertThat(attempts).containsExactly("SCHEDULED:FAILED:2027-01-09", "RETRY:PLANNED:2027-01-12");

        clock.set(LocalDate.of(2027, 1, 12));
        cob.catchUp(LocalDate.of(2027, 1, 12), "test");
        settle();

        assertThat(paypal.callCount("captureOutstanding")).isEqualTo(1);
        assertThat(queries.transactions(queries.loan(loan.loanId()))).extracting(TransactionView::source)
                .containsExactly("AUTOPAY", "AUTOPAY_RETRY");
        assertThat(jdbc.sql("SELECT status FROM lending.collection_attempt WHERE loan_id = :l AND kind = 'RETRY'")
                .param("l", loan.loanId()).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(queries.loan(loan.loanId()).bucket().name()).isEqualTo("CURRENT");
        assertIntegrity(loan.loanId());
    }

    @Test
    @Order(3)
    void tcMnd03_tcMnd05_pauseSuspendsAutopayAndPayoffCancelsIt() throws Exception {
        LoanSummary loan = disburse(UUID.randomUUID(), 1_200, 8, 9, LocalDate.of(2027, 1, 12));
        String sub = autopayOn(loan);
        assertThat(paypal.subscriptions.get(sub).nextBilling.toString()).startsWith("2027-02-09");

        // F5 option B approved: instalment 1 (9 Feb) moves to the end, so PayPal must not charge on 9 Feb
        mvc.perform(post("/internal/loans/{id}/reschedules", loan.loanId()).header("Authorization", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primitive\":\"DEFER_TO_END\",\"seq\":1,\"approvalRef\":\"appr-1\"}"))
                .andExpect(status().isOk());
        settle();
        assertThat(paypal.subscriptions.get(sub).status).isEqualTo("SUSPENDED");
        assertThat(lending.currentSchedule(loan.loanId()).version()).isEqualTo(2);

        // paying everything off cancels the mandate, and the loan falls back to manual collection
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-PAYOFF", loan.loanId(), 120_000));
        settle();
        assertThat(paypal.subscriptions.get(sub).status).isEqualTo("CANCELLED");
        var after = queries.loan(loan.loanId());
        assertThat(after.status().name()).isEqualTo("REPAID");
        assertThat(after.collectionMode().name()).isEqualTo("MANUAL");
        assertIntegrity(loan.loanId());
    }

    @Test
    @Order(4)
    void tcMnd07_memberCancellingInPayPalFallsBackToManualWithAKindMessage() throws Exception {
        LoanSummary loan = disburse(UUID.randomUUID(), 1_200, 8, 9, LocalDate.of(2027, 1, 12));
        reminders.upsertContact(loan.memberId(), "+14155550199", "es", "America/Los_Angeles");
        String sub = autopayOn(loan);
        paypal.subscriptions.get(sub).status = "CANCELLED";
        webhook(PayPalWebhooks.subscription(PayPalWebhooks.newEventId(), "BILLING.SUBSCRIPTION.CANCELLED", sub, "CANCELLED",
                150, Instant.now()));
        settle();

        assertThat(queries.loan(loan.loanId()).collectionMode().name()).isEqualTo("MANUAL");
        assertThat(reminders.forLoan(loan.loanId())).anySatisfy(r -> {
            assertThat(r.type()).isEqualTo("AUTOPAY_STOPPED");
            assertThat(r.body()).contains("pago automático");
        });
    }

    @Test
    @Order(5)
    void tcMnd06_raisingTheAmountNeedingConsentIsReportedAsDrift() throws Exception {
        LoanSummary loan = disburse(UUID.randomUUID(), 1_000, 7, 9, LocalDate.of(2027, 1, 12)); // 142, then 6 x 143
        String sub = autopayOn(loan);
        paypal.raiseNeedsConsent = true;
        clock.set(LocalDate.of(2027, 2, 9));
        webhook(PayPalWebhooks.saleCompleted(PayPalWebhooks.newEventId(), sub, "SALE-FEB", 14_200, 0));
        paypal.subscriptions.get(sub).nextBilling = LocalDate.of(2027, 3, 9).atTime(10, 0)
                .atZone(com.paypalseed.platform.kernel.BusinessClock.PROGRAM_ZONE).toInstant();
        settle();

        assertThat(paypal.subscriptions.get(sub).quantity).isEqualTo(142); // PayPal kept the old amount
        assertThat(paypal.callCount("revise")).isEqualTo(1);
        assertThat(reminders.forLoan(loan.loanId())).extracting(r -> r.type()).contains("AUTOPAY_ATTENTION");
    }

    @Test
    @Order(6)
    void tcRty04_c7_watchdogFindsAChargeWhoseWebhookNeverArrived() throws Exception {
        LoanSummary loan = disburse(UUID.randomUUID(), 1_200, 8, 9, LocalDate.of(2027, 2, 9));
        String sub = autopayOn(loan);
        paypal.subscriptions.get(sub).transactions.add(new SubscriptionTransaction("SALE-SILENT", "COMPLETED", 15_000, 0,
                Instant.now()));

        clock.set(LocalDate.of(2027, 3, 10)); // due 9 Mar + 1: the watchdog timer fires
        cob.catchUp(LocalDate.of(2027, 3, 10), "test");
        settle();

        assertThat(queries.transactions(queries.loan(loan.loanId()))).extracting(TransactionView::source)
                .containsExactly("RECONCILER");
        JsonNode first = json.valueToTree(lending.currentSchedule(loan.loanId()).instalments().get(0));
        assertThat(first.path("status").asText()).isEqualTo("PAID");
        assertIntegrity(loan.loanId());
    }
}
