package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.api.events.PaymentEvents;
import com.paypalseed.payments.api.events.PaymentEvents.DonationCaptured;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsDepositReceived;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsSubscriptionState;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsSubscriptionStateChanged;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.usecases.WebhookNormalizer.Handled;
import com.paypalseed.payments.application.usecases.WebhookNormalizer.Parked;
import com.paypalseed.payments.application.usecases.WebhookNormalizer.Result;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository.InboxEvent;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository;
import com.paypalseed.payments.infrastructure.persistence.MoneyRegistry;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository.SavingsSubscription;
import com.paypalseed.payments.infrastructure.persistence.TopUpRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** What the normaliser publishes for savings subscriptions and donations, so that savings and reserve need not be imported. */
class WebhookNormalizerSavingsTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final SavingsSubscriptionRepository savingsSubscriptions = mock(SavingsSubscriptionRepository.class);
    private final MandateRepository mandates = mock(MandateRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher events = published::add;
    private final WebhookNormalizer normalizer = new WebhookNormalizer(new ObjectMapper(), mandates, mock(MoneyRegistry.class),
            mock(MoneyFacts.class), events, mock(PayPalGateway.class), mock(PayoutRepository.class), mock(PayoutFacts.class),
            savingsSubscriptions, mock(TopUpRepository.class), mock(TopUpFacts.class));

    private static InboxEvent event(String type, String resource) {
        String body = "{\"id\":\"WH-1\",\"event_type\":\"" + type + "\",\"resource\":" + resource + "}";
        return new InboxEvent(UUID.randomUUID(), "WH-1", type, body, 0);
    }

    private void knownSubscription() {
        when(savingsSubscriptions.bySubscription("I-SAVE")).thenReturn(Optional.of(
                new SavingsSubscription(UUID.randomUUID(), GOAL, MEMBER, "req", 2_500, "I-SAVE", null, "APPROVAL_PENDING")));
    }

    @Test
    void aSaleOnASavingsSubscriptionBecomesADepositForItsGoal() {
        knownSubscription();

        Result result = normalizer.handle(event("PAYMENT.SALE.COMPLETED", """
                {"id":"SALE-9","billing_agreement_id":"I-SAVE","amount":{"total":"25.00","currency":"USD"},
                 "transaction_fee":{"value":"0.90","currency":"USD"},"create_time":"2026-11-10T10:00:00Z"}"""));

        assertThat(result).isInstanceOf(Handled.class);
        assertThat(published).singleElement().isInstanceOfSatisfying(SavingsDepositReceived.class, e -> {
            assertThat(e.goalId()).isEqualTo(GOAL);
            assertThat(e.memberId()).isEqualTo(MEMBER);
            assertThat(e.paypalTxnId()).isEqualTo("SALE-9");
            assertThat(e.amountCents()).isEqualTo(2_500);
            assertThat(e.feeCents()).isEqualTo(90);
            assertThat(e.subscriptionId()).isEqualTo("I-SAVE");
        });
        verify(mandates, never()).bySubscription("I-SAVE");
    }

    private static String body(String type, String eventCreateTime, String resource) {
        String eventTime = eventCreateTime == null ? "" : ",\"create_time\":\"" + eventCreateTime + "\"";
        return "{\"id\":\"WH-1\",\"event_type\":\"" + type + "\"" + eventTime + ",\"resource\":" + resource + "}";
    }

    private Instant stateChangeTimeOf(String type, String eventCreateTime, String resource) {
        knownSubscription();
        published.clear();
        normalizer.handle(new InboxEvent(UUID.randomUUID(), "WH-1", type, body(type, eventCreateTime, resource), 0));
        return ((SavingsSubscriptionStateChanged) published.getFirst()).occurredAt();
    }

    @Test
    void aSubscriptionStateChangeIsDatedByTheResourceUpdateTimeNotItsCreationTime() {
        Instant at = stateChangeTimeOf("BILLING.SUBSCRIPTION.SUSPENDED", "2026-11-12T09:00:05Z", """
                {"id":"I-SAVE","status":"SUSPENDED","custom_id":"goal:%s",
                 "create_time":"2026-11-01T08:00:00Z","update_time":"2026-11-12T09:00:00Z"}""".formatted(GOAL));

        assertThat(at).isEqualTo(Instant.parse("2026-11-12T09:00:00Z"));
    }

    @Test
    void withoutAnUpdateTimeTheEventsOwnTimeDatesTheChange() {
        Instant at = stateChangeTimeOf("BILLING.SUBSCRIPTION.CANCELLED", "2026-11-13T10:30:00Z", """
                {"id":"I-SAVE","status":"CANCELLED","custom_id":"goal:%s","create_time":"2026-11-01T08:00:00Z"}""".formatted(GOAL));

        assertThat(at).isEqualTo(Instant.parse("2026-11-13T10:30:00Z"));
    }

    @Test
    void twoChangesOfOneSubscriptionCanBeOrdered() {
        Instant activated = stateChangeTimeOf("BILLING.SUBSCRIPTION.ACTIVATED", null, """
                {"id":"I-SAVE","status":"ACTIVE","custom_id":"goal:%s",
                 "create_time":"2026-11-01T08:00:00Z","update_time":"2026-11-02T08:00:00Z"}""".formatted(GOAL));
        Instant suspended = stateChangeTimeOf("BILLING.SUBSCRIPTION.SUSPENDED", null, """
                {"id":"I-SAVE","status":"SUSPENDED","custom_id":"goal:%s",
                 "create_time":"2026-11-01T08:00:00Z","update_time":"2026-11-09T08:00:00Z"}""".formatted(GOAL));

        assertThat(suspended).isAfter(activated);
    }

    @Test
    void everySubscriptionStatusWebhookMapsToOneState() {
        knownSubscription();
        record Case(String type, SavingsSubscriptionState state) {
        }
        List<Case> cases = List.of(new Case("BILLING.SUBSCRIPTION.ACTIVATED", SavingsSubscriptionState.ACTIVE),
                new Case("BILLING.SUBSCRIPTION.RE-ACTIVATED", SavingsSubscriptionState.ACTIVE),
                new Case("BILLING.SUBSCRIPTION.SUSPENDED", SavingsSubscriptionState.SUSPENDED),
                new Case("BILLING.SUBSCRIPTION.CANCELLED", SavingsSubscriptionState.CANCELLED),
                new Case("BILLING.SUBSCRIPTION.EXPIRED", SavingsSubscriptionState.EXPIRED));
        for (Case c : cases) {
            published.clear();
            Result result = normalizer.handle(event(c.type(), "{\"id\":\"I-SAVE\",\"status\":\"X\",\"custom_id\":\"goal:" + GOAL + "\"}"));
            assertThat(result).isInstanceOf(Handled.class);
            assertThat(published).singleElement().isInstanceOfSatisfying(SavingsSubscriptionStateChanged.class, e -> {
                assertThat(e.state()).isEqualTo(c.state());
                assertThat(e.goalId()).isEqualTo(GOAL);
            });
        }
    }

    @Test
    void createdAndUpdatedChangeNothingTheGoalCaresAbout() {
        knownSubscription();

        normalizer.handle(event("BILLING.SUBSCRIPTION.CREATED", "{\"id\":\"I-SAVE\",\"status\":\"APPROVAL_PENDING\"}"));
        normalizer.handle(event("BILLING.SUBSCRIPTION.UPDATED", "{\"id\":\"I-SAVE\",\"status\":\"ACTIVE\"}"));

        assertThat(published).isEmpty();
    }

    @Test
    void aSavingsSubscriptionWebhookBeforeOurRowCommitsWaitsForIt() {
        when(savingsSubscriptions.bySubscription("I-EARLY")).thenReturn(Optional.empty());

        Result result = normalizer.handle(event("BILLING.SUBSCRIPTION.ACTIVATED",
                "{\"id\":\"I-EARLY\",\"status\":\"ACTIVE\",\"custom_id\":\"goal:" + GOAL + "\"}"));

        assertThat(result).isEqualTo(new Parked("subscription:I-EARLY"));
        assertThat(published).isEmpty();
    }

    @Test
    void aCaptureForTheReserveFundBecomesADonationFact() {
        Result result = normalizer.handle(event("PAYMENT.CAPTURE.COMPLETED", """
                {"id":"CAP-1","status":"COMPLETED","custom_id":"reserve-donation:00000000-0000-0000-0000-0000000000c3",
                 "amount":{"value":"20.00","currency_code":"USD"},
                 "seller_receivable_breakdown":{"paypal_fee":{"value":"0.88","currency_code":"USD"}},
                 "create_time":"2026-11-10T10:00:00Z"}"""));

        assertThat(result).isInstanceOf(Handled.class);
        assertThat(published).singleElement().isInstanceOfSatisfying(DonationCaptured.class, e -> {
            assertThat(e.paypalTxnId()).isEqualTo("CAP-1");
            assertThat(e.customId()).isEqualTo("reserve-donation:00000000-0000-0000-0000-0000000000c3");
            assertThat(e.grossCents()).isEqualTo(2_000);
            assertThat(e.feeCents()).isEqualTo(88);
        });
    }

    @Test
    void aLoanCaptureStillGoesToTheLoanFunnel() {
        MoneyFacts facts = mock(MoneyFacts.class);
        WebhookNormalizer loanNormalizer = new WebhookNormalizer(new ObjectMapper(), mandates, mock(MoneyRegistry.class), facts,
                events, mock(PayPalGateway.class), mock(PayoutRepository.class), mock(PayoutFacts.class), savingsSubscriptions,
                mock(TopUpRepository.class), mock(TopUpFacts.class));
        UUID loan = UUID.randomUUID();

        Result result = loanNormalizer.handle(event("PAYMENT.CAPTURE.COMPLETED", """
                {"id":"CAP-2","custom_id":"loan:%s:CATCH_UP","amount":{"value":"10.00","currency_code":"USD"},
                 "create_time":"2026-11-10T10:00:00Z"}""".formatted(loan)));

        assertThat(result).isInstanceOf(Handled.class);
        assertThat(published).noneMatch(e -> e instanceof DonationCaptured);
        verify(facts).captured(loan, "CAP-2", "CAPTURE", 1_000, 0, java.time.Instant.parse("2026-11-10T10:00:00Z"),
                PaymentEvents.Source.CHECKOUT, "paypal-event:WH-1");
    }
}
