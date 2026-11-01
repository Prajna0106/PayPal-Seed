package com.paypalseed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.support.FakePayPal.SimPayout;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Helpers shared by the integration tests of the flows that move money through PayPal (savings and loan payouts). */
abstract class MoneyFlowSupport extends IntegrationTest {

    @Autowired
    protected IdentityFacade identity;
    @Autowired
    protected ObjectMapper json;

    /** A signed-up member with a PayPal email, so a payout has somewhere to go. */
    protected UUID newMember() {
        return identity.loginWithPayPal(new PayPalLogin("PAYER-" + UUID.randomUUID(), "ana@example.test", "Ana", true, "en"))
            .member().memberId();
    }

    /** The PayPal payer id the member logged in with: where a PayPal wallet payout goes. */
    protected String payerIdOf(UUID member) {
        return identity.payoutRecipient(member).orElseThrow().payerId();
    }

    protected static MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request, UUID member) {
        return request.header("X-Seed-Member-Id", member.toString()).contentType(MediaType.APPLICATION_JSON);
    }

    protected static MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request, UUID staff, String roles) {
        return request.header("X-Seed-Staff-Id", staff.toString()).header("X-Seed-Staff-Roles", roles)
            .contentType(MediaType.APPLICATION_JSON);
    }

    protected JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Creates a goal the old way (a client made subscription id), active at once; allowed in the test profile. */
    protected JsonNode legacyGoal(UUID member, double monthly) throws Exception {
        return read(mvc.perform(asMember(post("/api/v1/savings/goals"), member).content("""
                {"name":"Mixer","targetAmount":500.00,"monthlyAmount":%s,"payPalSubscriptionId":"legacy-%s"}
                """.formatted(monthly, UUID.randomUUID()))).andExpect(status().isCreated()).andReturn());
    }

    protected JsonNode legacyTopUp(UUID member, String goalId, double amount) throws Exception {
        return read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/topup", goalId), member).content("""
                {"amount":%s,"payPalCaptureId":"legacy-cap-%s"}
                """.formatted(amount, UUID.randomUUID()))).andExpect(status().isOk()).andReturn());
    }

    protected JsonNode goal(UUID member, String goalId) throws Exception {
        return read(mvc.perform(asMember(get("/api/v1/savings/goals/{id}", goalId), member)).andExpect(status().isOk()).andReturn());
    }

    protected long memberSavingsInBooks(UUID member) {
        return ledger.balanceCents(AccountRef.of(AccountType.MEMBER_SAVINGS, member));
    }

    /** Every journal entry balances, so debits and credits over the whole ledger cancel. */
    protected void assertLedgerBalanced() {
        Long net = jdbc.sql("""
                SELECT COALESCE(SUM(CASE side WHEN 'DEBIT' THEN amount_cents ELSE -amount_cents END), 0)
                  FROM ledger.journal_line
                """).query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(net).as("ledger debits minus credits").isZero();
    }

    // ---- PayPal webhooks, shaped like the sandbox sends them ----

    private static String envelope(String eventId, String type, String resource) {
        return """
                {"id":"%s","event_version":"1.0","create_time":"%s","event_type":"%s","resource":%s}
                """.formatted(eventId, Instant.now(), type, resource);
    }

    protected static String newEventId() {
        return "WH-" + UUID.randomUUID();
    }

    /** A payout item report for the payout PayPal made for {@code referenceId}. */
    protected String payoutItemEvent(String eventId, String type, SimPayout p, String transactionStatus, String error) {
        String errors = error == null ? "" : ",\"errors\":{\"name\":\"%s\",\"message\":\"simulated\"}".formatted(error);
        return envelope(eventId, type, """
                {"transaction_id":"TX-%s","payout_item_fee":{"currency":"USD","value":"0.25"},"payout_batch_id":"%s",
                 "payout_item_id":"%s","sender_batch_id":"%s","transaction_status":"%s",
                 "payout_item":{"recipient_type":"%s","amount":{"currency":"USD","value":"%s"},"receiver":"%s",
                                "sender_item_id":"%s","recipient_wallet":"%s"},"time_processed":"%s"%s}
                """.formatted(p.itemId, p.batchId, p.itemId, p.senderBatchId, transactionStatus, p.recipientType,
                com.paypalseed.platform.kernel.Money.ofCents(p.amountCents).toDecimalString(), p.receiver, p.senderItemId,
                p.wallet, Instant.now(), errors));
    }

    protected void payoutSucceededWebhook(SimPayout p) throws Exception {
        paypal.settlePayout(p.itemId, "SUCCESS", null);
        webhook(payoutItemEvent(newEventId(), "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", p, "SUCCESS", null));
    }

    protected void payoutFailedWebhook(SimPayout p, String reason) throws Exception {
        paypal.settlePayout(p.itemId, "FAILED", reason);
        webhook(payoutItemEvent(newEventId(), "PAYMENT.PAYOUTS-ITEM.FAILED", p, "FAILED", reason));
    }

    protected void payoutReturnedWebhook(SimPayout p) throws Exception {
        paypal.settlePayout(p.itemId, "RETURNED", null);
        webhook(payoutItemEvent(newEventId(), "PAYMENT.PAYOUTS-ITEM.RETURNED", p, "RETURNED", null));
    }

    protected void subscriptionWebhook(String type, String subscriptionId, String status) throws Exception {
        webhook(envelope(newEventId(), type, """
                {"id":"%s","status":"%s","custom_id":"goal:ignored","quantity":"25"}
                """.formatted(subscriptionId, status)));
    }

    protected void saleWebhook(String eventId, String subscriptionId, String saleId, long cents) throws Exception {
        webhook(envelope(eventId, "PAYMENT.SALE.COMPLETED", """
                {"id":"%s","state":"completed","billing_agreement_id":"%s","amount":{"total":"%s","currency":"USD"},
                 "transaction_fee":{"value":"0.90","currency":"USD"},"create_time":"%s"}
                """.formatted(saleId, subscriptionId, com.paypalseed.platform.kernel.Money.ofCents(cents).toDecimalString(),
                Instant.now())));
    }

    protected void topUpCaptureWebhook(String eventId, String captureId, String orderId, String goalId, long cents) throws Exception {
        webhook(envelope(eventId, "PAYMENT.CAPTURE.COMPLETED", """
                {"id":"%s","status":"COMPLETED","custom_id":"goal:%s:TOPUP","amount":{"value":"%s","currency_code":"USD"},
                 "supplementary_data":{"related_ids":{"order_id":"%s"}},
                 "seller_receivable_breakdown":{"paypal_fee":{"value":"0.00","currency_code":"USD"}},"create_time":"%s"}
                """.formatted(captureId, goalId, com.paypalseed.platform.kernel.Money.ofCents(cents).toDecimalString(), orderId,
                Instant.now())));
    }
}
