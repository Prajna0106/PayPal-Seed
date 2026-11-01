package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutView;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import com.paypalseed.payments.api.dto.PaymentsDtos.SendPayout;
import com.paypalseed.payments.api.events.PaymentEvents.PayoutFailed;
import com.paypalseed.payments.api.events.PaymentEvents.PayoutReturned;
import com.paypalseed.payments.api.events.PaymentEvents.PayoutSucceeded;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.FakePayPal.PayoutOutcome;
import com.paypalseed.support.FakePayPal.SimPayout;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

/**
 * Payouts end to end against the PayPal simulator: send, replay, conflicting replay, every outcome PayPal
 * can report by API or webhook producing exactly one event, duplicate deliveries, and bad signatures.
 */
@Import({IntegrationTest.Fakes.class, PayoutFlowIT.Recording.class})
class PayoutFlowIT extends IntegrationTest {

    /** Collects the payout events so a test can count them per payout reference. */
    public static class EventLog {
        final List<PayoutSucceeded> succeeded = new CopyOnWriteArrayList<>();
        final List<PayoutFailed> failed = new CopyOnWriteArrayList<>();
        final List<PayoutReturned> returned = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(PayoutSucceeded e) {
            succeeded.add(e);
        }

        @EventListener
        public void on(PayoutFailed e) {
            failed.add(e);
        }

        @EventListener
        public void on(PayoutReturned e) {
            returned.add(e);
        }

        List<PayoutSucceeded> succeeded(String ref) {
            return succeeded.stream().filter(e -> e.referenceId().equals(ref)).toList();
        }

        List<PayoutFailed> failed(String ref) {
            return failed.stream().filter(e -> e.referenceId().equals(ref)).toList();
        }

        List<PayoutReturned> returned(String ref) {
            return returned.stream().filter(e -> e.referenceId().equals(ref)).toList();
        }

        int total(String ref) {
            return succeeded(ref).size() + failed(ref).size() + returned(ref).size();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {
        @Bean
        EventLog eventLog() {
            return new EventLog();
        }
    }

    @Autowired
    PaymentsFacade payments;
    @Autowired
    EventLog events;

    private static String newRef() {
        return "disb-" + UUID.randomUUID();
    }

    private static SendPayout send(String ref, long cents) {
        return new SendPayout(ref, cents, RecipientType.EMAIL, "member@example.test", PayoutWallet.PAYPAL, "Your Seed loan");
    }

    private static String envelope(String eventId, String type, String resource) {
        return """
                {"id":"%s","event_version":"1.0","create_time":"%s","event_type":"%s","resource":%s}
                """.formatted(eventId, Instant.now(), type, resource);
    }

    private static String itemEvent(String eventId, String type, SimPayout p, String transactionStatus, String error) {
        String errors = error == null ? "" : ",\"errors\":{\"name\":\"%s\",\"message\":\"simulated\"}".formatted(error);
        return envelope(eventId, type, """
                {"transaction_id":"TX-%s","payout_item_fee":{"currency":"USD","value":"0.25"},"payout_batch_id":"%s",
                 "payout_item_id":"%s","sender_batch_id":"%s","transaction_status":"%s",
                 "payout_item":{"recipient_type":"%s","amount":{"currency":"USD","value":"50.00"},"receiver":"%s",
                                "sender_item_id":"%s","recipient_wallet":"%s"},"time_processed":"%s"%s}
                """.formatted(p.itemId, p.batchId, p.itemId, p.senderBatchId, transactionStatus, p.recipientType, p.receiver,
                p.senderItemId, p.wallet, Instant.now(), errors));
    }

    private static String batchEvent(String eventId, String type, SimPayout p, String batchStatus) {
        return envelope(eventId, type, """
                {"batch_header":{"payout_batch_id":"%s","batch_status":"%s",
                 "sender_batch_header":{"sender_batch_id":"%s"}}}
                """.formatted(p.batchId, batchStatus, p.senderBatchId));
    }

    private String inboxStatus(String eventId) {
        return jdbc.sql("SELECT status FROM payments.webhook_inbox WHERE paypal_event_id = ?").param(eventId)
                .query(String.class).optional().orElse("ABSENT");
    }

    // ---- send and replay ----

    @Test
    void sendReplayAndConflictingReplay() {
        String ref = newRef();

        PayoutView sent = payments.sendPayout(send(ref, 5_000));

        assertThat(sent.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(sent.paypalBatchId()).isPresent();
        assertThat(sent.paypalItemId()).isPresent();
        assertThat(sent.amountCents()).isEqualTo(5_000);
        assertThat(paypal.payoutsByBatch.values().stream().filter(p -> p.senderItemId.equals(ref)).count()).isEqualTo(1);
        SimPayout sim = paypal.payoutOfReference(ref);
        assertThat(sim.senderBatchId).isEqualTo("seed:" + ref);
        assertThat(sim.amountCents).isEqualTo(5_000);
        assertThat(sim.recipientType).isEqualTo("EMAIL");
        assertThat(sim.wallet).isEqualTo("PAYPAL");

        assertThat(payments.sendPayout(send(ref, 5_000))).isEqualTo(sent);
        assertThat(payments.payoutOf(ref)).contains(sent);
        assertThat(paypal.callCount("createPayout")).isEqualTo(1);

        assertThatThrownBy(() -> payments.sendPayout(send(ref, 5_001)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(PaymentsErrors.PAYOUT_REFERENCE_REUSED);
                    assertThat(e.errorCode().kind()).isEqualTo(ErrorCode.Kind.CONFLICT);
                });
        assertThatThrownBy(() -> payments.sendPayout(new SendPayout(ref, 5_000, RecipientType.EMAIL, "other@example.test",
                PayoutWallet.PAYPAL, null))).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PaymentsErrors.PAYOUT_REFERENCE_REUSED));
        assertThatThrownBy(() -> payments.sendPayout(new SendPayout(ref, 5_000, RecipientType.EMAIL, "member@example.test",
                PayoutWallet.VENMO, null))).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PaymentsErrors.PAYOUT_REFERENCE_REUSED));
        assertThat(paypal.callCount("createPayout")).isEqualTo(1);
        assertThat(events.total(ref)).isZero();
    }

    @Test
    void unknownReferenceHasNoPayout() {
        assertThat(payments.payoutOf("never-sent")).isEmpty();
    }

    @Test
    void aVenmoPayoutToAPhoneNumberReachesPayPalAsSuch() {
        String ref = newRef();

        payments.sendPayout(new SendPayout(ref, 2_500, RecipientType.PHONE, "+15551230000", PayoutWallet.VENMO, "Loan"));

        SimPayout sim = paypal.payoutOfReference(ref);
        assertThat(sim.wallet).isEqualTo("VENMO");
        assertThat(sim.recipientType).isEqualTo("PHONE");
        assertThat(sim.receiver).isEqualTo("+15551230000");
        assertThat(payments.payoutOf(ref).orElseThrow().wallet()).isEqualTo(PayoutWallet.VENMO);
    }

    // ---- outcomes by webhook ----

    @Test
    void pendingPayoutThenItemSucceededPublishesOneEventEvenWhenDeliveredTwiceAndEchoedByTheBatch() throws Exception {
        String ref = newRef();
        PayoutView sent = payments.sendPayout(send(ref, 5_000));
        SimPayout sim = paypal.payoutOfReference(ref);
        paypal.settlePayout(sim.itemId, "SUCCESS", null);
        String body = itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", sim, "SUCCESS", null);

        webhook(body);
        webhook(body); // PayPal redelivers the same event id
        webhook(batchEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTSBATCH.SUCCESS", sim, "SUCCESS"));
        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", sim, "SUCCESS", null)); // new id, same fact
        settle();

        assertThat(events.succeeded(ref)).singleElement().satisfies(e -> {
            assertThat(e.payoutId()).isEqualTo(sent.payoutId());
            assertThat(e.amountCents()).isEqualTo(5_000);
            assertThat(e.wallet()).isEqualTo(PayoutWallet.PAYPAL);
            assertThat(e.feeCents()).isEqualTo(25);
            assertThat(e.paypalTransactionId()).isEqualTo("TX-" + sim.itemId);
        });
        assertThat(events.total(ref)).isEqualTo(1);
        PayoutView done = payments.payoutOf(ref).orElseThrow();
        assertThat(done.status()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(done.feeCents()).isEqualTo(25);
        assertThat(jdbc.sql("""
                SELECT t.to_status FROM payments.payout_transition t JOIN payments.payout p ON p.id = t.payout_id
                 WHERE p.reference_id = ?""").param(ref).query(String.class).list())
                .containsExactlyInAnyOrder("REQUESTED", "PENDING", "SUCCEEDED");
    }

    @Test
    void duplicateDeliveryOfTheSameWebhookIsAcknowledgedAndIgnored() throws Exception {
        String ref = newRef();
        payments.sendPayout(send(ref, 1_000));
        SimPayout sim = paypal.payoutOfReference(ref);
        String eventId = PayPalWebhooks.newEventId();
        String body = itemEvent(eventId, "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", sim, "SUCCESS", null);

        String first = mvc.perform(post("/api/v1/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("paypal-transmission-sig", "ok")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String second = mvc.perform(post("/api/v1/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("paypal-transmission-sig", "ok")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        settle();

        assertThat(first).contains("accepted");
        assertThat(second).contains("duplicate");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payments.webhook_inbox WHERE paypal_event_id = ?").param(eventId)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(events.succeeded(ref)).hasSize(1);
    }

    @Test
    void itemFailedCarriesPayPalsReasonAndLaterContradictionsAreIgnored() throws Exception {
        String ref = newRef();
        payments.sendPayout(send(ref, 3_000));
        SimPayout sim = paypal.payoutOfReference(ref);

        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.FAILED", sim, "FAILED", "RECEIVER_UNREGISTERED"));
        settle();
        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", sim, "SUCCESS", null));
        settle();

        assertThat(events.failed(ref)).singleElement().satisfies(e -> {
            assertThat(e.reasonCode()).isEqualTo("RECEIVER_UNREGISTERED");
            assertThat(e.terminal()).isTrue();
            assertThat(e.amountCents()).isEqualTo(3_000);
        });
        assertThat(events.total(ref)).isEqualTo(1);
        PayoutView view = payments.payoutOf(ref).orElseThrow();
        assertThat(view.status()).isEqualTo(PayoutStatus.FAILED);
        assertThat(view.failureReason()).contains("RECEIVER_UNREGISTERED");
    }

    @Test
    void itemBlockedIsAFinalFailure() throws Exception {
        String ref = newRef();
        payments.sendPayout(send(ref, 3_000));
        SimPayout sim = paypal.payoutOfReference(ref);

        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.BLOCKED", sim, "BLOCKED", null));
        settle();

        assertThat(events.failed(ref)).singleElement().satisfies(e -> {
            assertThat(e.reasonCode()).isEqualTo("BLOCKED");
            assertThat(e.terminal()).isTrue();
        });
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.BLOCKED);
    }

    @Test
    void itemReturnedFromPendingPublishesOneReturn() throws Exception {
        String ref = newRef();
        payments.sendPayout(send(ref, 4_000));
        SimPayout sim = paypal.payoutOfReference(ref);

        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.RETURNED", sim, "RETURNED", null));
        settle();

        assertThat(events.returned(ref)).singleElement().satisfies(e -> {
            assertThat(e.reasonCode()).isEqualTo("RETURNED");
            assertThat(e.amountCents()).isEqualTo(4_000);
        });
        assertThat(events.total(ref)).isEqualTo(1);
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.RETURNED);
    }

    @Test
    void unclaimedIsANonFinalFailureAndExpiresToReturned() throws Exception {
        String ref = newRef();
        payments.sendPayout(send(ref, 4_000));
        SimPayout sim = paypal.payoutOfReference(ref);

        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.UNCLAIMED", sim, "UNCLAIMED", null));
        settle();

        assertThat(events.failed(ref)).singleElement().satisfies(e -> {
            assertThat(e.reasonCode()).isEqualTo("UNCLAIMED");
            assertThat(e.terminal()).isFalse();
        });
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.UNCLAIMED);

        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.RETURNED", sim, "RETURNED", null));
        settle();

        assertThat(events.returned(ref)).singleElement().satisfies(e -> assertThat(e.reasonCode()).isEqualTo("UNCLAIMED_EXPIRED"));
        assertThat(events.total(ref)).isEqualTo(2);
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.RETURNED);
    }

    @Test
    void unclaimedThenClaimedSucceeds() throws Exception {
        String ref = newRef();
        payments.sendPayout(send(ref, 4_000));
        SimPayout sim = paypal.payoutOfReference(ref);
        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.UNCLAIMED", sim, "UNCLAIMED", null));
        settle();

        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", sim, "SUCCESS", null));
        settle();

        assertThat(events.succeeded(ref)).hasSize(1);
        assertThat(events.failed(ref)).hasSize(1);
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.SUCCEEDED);
    }

    @Test
    void batchDeniedFailsThePayoutOnce() throws Exception {
        String ref = newRef();
        payments.sendPayout(send(ref, 6_000));
        SimPayout sim = paypal.payoutOfReference(ref);

        webhook(batchEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTSBATCH.DENIED", sim, "DENIED"));
        settle();

        assertThat(events.failed(ref)).singleElement().satisfies(e -> {
            assertThat(e.reasonCode()).isEqualTo("BATCH_DENIED");
            assertThat(e.terminal()).isTrue();
        });
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.FAILED);
    }

    @Test
    void batchSuccessReadsTheItemBackBeforeTrustingIt() throws Exception {
        String ref = newRef();
        payments.sendPayout(send(ref, 6_000));
        SimPayout sim = paypal.payoutOfReference(ref);

        // PayPal processed the batch, but the item itself is still pending: nothing to announce yet
        webhook(batchEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTSBATCH.SUCCESS", sim, "SUCCESS"));
        settle();
        assertThat(events.total(ref)).isZero();
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.PENDING);

        paypal.settlePayout(sim.itemId, "SUCCESS", null);
        webhook(batchEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTSBATCH.SUCCESS", sim, "SUCCESS"));
        settle();

        assertThat(events.succeeded(ref)).hasSize(1);
        assertThat(events.total(ref)).isEqualTo(1);
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.SUCCEEDED);
    }

    @Test
    void aWebhookThatArrivesBeforeTheSendCommitsWaitsForIt() throws Exception {
        String ref = newRef();
        SimPayout early = FakePayPal.simPayoutForWebhook("BATCH-early-" + ref, "ITEM-early-" + ref, "seed:" + ref, ref);
        String eventId = PayPalWebhooks.newEventId();
        webhook(itemEvent(eventId, "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", early, "SUCCESS", null));
        settle();
        assertThat(inboxStatus(eventId)).isEqualTo("PARKED");

        payments.sendPayout(send(ref, 5_000));
        settle();

        assertThat(inboxStatus(eventId)).isEqualTo("PROCESSED");
        assertThat(events.succeeded(ref)).hasSize(1);
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.SUCCEEDED);
    }

    @Test
    void aPayoutWebhookForAnotherSendersBatchIsIgnored() throws Exception {
        SimPayout foreign = FakePayPal.simPayoutForWebhook("BATCH-other", "ITEM-other", "some-other-system-1", "other");
        String eventId = PayPalWebhooks.newEventId();

        webhook(itemEvent(eventId, "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", foreign, "SUCCESS", null));
        settle();

        assertThat(inboxStatus(eventId)).isEqualTo("IGNORED");
    }

    @Test
    void aWebhookWithABadSignatureIsRefusedAndNeverStored() throws Exception {
        String ref = newRef();
        payments.sendPayout(send(ref, 5_000));
        SimPayout sim = paypal.payoutOfReference(ref);
        String eventId = PayPalWebhooks.newEventId();

        mvc.perform(post("/api/v1/webhooks/paypal").contentType(MediaType.APPLICATION_JSON)
                        .content(itemEvent(eventId, "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", sim, "SUCCESS", null))
                        .header("paypal-transmission-sig", "bad"))
                .andExpect(status().is4xxClientError());
        settle();

        assertThat(inboxStatus(eventId)).isEqualTo("ABSENT");
        assertThat(events.total(ref)).isZero();
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.PENDING);
    }

    // ---- outcomes by API ----

    @Test
    void instantSuccessIsAnnouncedOnceDespiteTheWebhooksThatFollow() throws Exception {
        paypal.payoutOutcome = PayoutOutcome.INSTANT_SUCCESS;
        String ref = newRef();

        PayoutView view = payments.sendPayout(send(ref, 7_500));

        assertThat(view.status()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(view.paypalTransactionId()).isPresent();
        assertThat(events.succeeded(ref)).hasSize(1);
        SimPayout sim = paypal.payoutOfReference(ref);
        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", sim, "SUCCESS", null));
        webhook(batchEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTSBATCH.SUCCESS", sim, "SUCCESS"));
        settle();
        assertThat(events.total(ref)).isEqualTo(1);
        assertThat(payments.sendPayout(send(ref, 7_500)).status()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(paypal.callCount("createPayout")).isEqualTo(1);
    }

    @Test
    void anImmediateFailureFromPayPalIsAnnouncedOnce() throws Exception {
        paypal.payoutOutcome = PayoutOutcome.FAILED;
        String ref = newRef();

        PayoutView view = payments.sendPayout(send(ref, 7_500));

        assertThat(view.status()).isEqualTo(PayoutStatus.FAILED);
        SimPayout sim = paypal.payoutOfReference(ref);
        webhook(itemEvent(PayPalWebhooks.newEventId(), "PAYMENT.PAYOUTS-ITEM.FAILED", sim, "FAILED", "RECEIVER_UNREGISTERED"));
        settle();
        assertThat(events.failed(ref)).singleElement().satisfies(e -> assertThat(e.reasonCode()).isEqualTo("RECEIVER_UNREGISTERED"));
        assertThat(events.total(ref)).isEqualTo(1);
    }

    @Test
    void aRefusalFromPayPalFailsThePayoutAndSpendsTheReference() {
        paypal.failNext("createPayout", FakePayPal.refused("INSUFFICIENT_FUNDS"));
        String ref = newRef();

        PayoutView view = payments.sendPayout(send(ref, 9_000));

        assertThat(view.status()).isEqualTo(PayoutStatus.FAILED);
        assertThat(view.failureReason()).contains("INSUFFICIENT_FUNDS");
        assertThat(events.failed(ref)).singleElement().satisfies(e -> {
            assertThat(e.reasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(e.terminal()).isTrue();
        });
        assertThat(payments.sendPayout(send(ref, 9_000))).isEqualTo(view);
        assertThat(paypal.callCount("createPayout")).isEqualTo(1);
        assertThat(paypal.payoutsByBatch.values().stream().anyMatch(p -> p.senderItemId.equals(ref))).isFalse();
    }

    @Test
    void aTransientPayPalFailureLeavesNothingBehindAndTheRetryReusesTheRequestId() {
        paypal.failNext("createPayout", FakePayPal.unavailable());
        String ref = newRef();

        assertThatThrownBy(() -> payments.sendPayout(send(ref, 9_000))).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.DEPENDENCY_UNAVAILABLE));
        assertThat(payments.payoutOf(ref)).isEmpty();

        PayoutView view = payments.sendPayout(send(ref, 9_000));

        assertThat(view.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(paypal.calls.stream().filter(c -> c.equals("createPayout payout:" + ref)).count()).isEqualTo(2);
        assertThat(paypal.payoutsByBatch.values().stream().filter(p -> p.senderItemId.equals(ref)).count()).isEqualTo(1);
        assertThat(events.total(ref)).isZero();
    }

    // ---- database protection ----

    @Test
    void thePayoutFactsCannotBeRewrittenOrDeleted() {
        String ref = newRef();
        payments.sendPayout(send(ref, 1_200));
        UUID id = payments.payoutOf(ref).orElseThrow().payoutId();

        assertThatThrownBy(() -> jdbc.sql("UPDATE payments.payout_transition SET reason = 'edited' WHERE payout_id = ?")
                .param(id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM payments.payout_transition WHERE payout_id = ?").param(id).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM payments.payout WHERE id = ?").param(id).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE payments.payout SET amount_cents = 99999 WHERE id = ?").param(id).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE payments.payout SET receiver = 'thief@example.test' WHERE id = ?").param(id)
                .update()).isInstanceOf(DataAccessException.class);
        jdbc.sql("UPDATE payments.payout SET status = 'SUCCEEDED' WHERE id = ?").param(id).update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE payments.payout SET status = 'PENDING' WHERE id = ?").param(id).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE payments.payout SET status = 'FAILED' WHERE id = ?").param(id).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(payments.payoutOf(ref).orElseThrow().status()).isEqualTo(PayoutStatus.SUCCEEDED);
    }
}
