package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsDepositReceived;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.Subscription;
import com.paypalseed.payments.application.usecases.WebhookNormalizer.Handled;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** PayPal's next charge time of a savings subscription is kept from its reports, and is never made up (ADR 0027). */
class WebhookNormalizerNextBillingTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Instant NEXT = Instant.parse("2026-12-03T17:00:00Z");

    private final SavingsSubscriptionRepository subscriptions = mock(SavingsSubscriptionRepository.class);
    private final PayPalGateway paypal = mock(PayPalGateway.class);
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher events = published::add;
    private final WebhookNormalizer normalizer = new WebhookNormalizer(new ObjectMapper(), mock(MandateRepository.class),
            mock(MoneyRegistry.class), mock(MoneyFacts.class), events, paypal, mock(PayoutRepository.class), mock(PayoutFacts.class),
            subscriptions, mock(TopUpRepository.class), mock(TopUpFacts.class));

    @BeforeEach
    void knownSubscription() {
        when(subscriptions.bySubscription("I-SAVE")).thenReturn(Optional.of(
                new SavingsSubscription(UUID.randomUUID(), GOAL, MEMBER, "req", 2_500, "I-SAVE", null, "ACTIVE")));
    }

    private static InboxEvent subscriptionEvent(String type, String status, String billingInfo) {
        String body = "{\"id\":\"WH-1\",\"event_type\":\"" + type + "\",\"create_time\":\"2026-11-10T10:00:00Z\",\"resource\":"
                + "{\"id\":\"I-SAVE\",\"status\":\"" + status + "\",\"custom_id\":\"goal:" + GOAL + "\"" + billingInfo + "}}";
        return new InboxEvent(UUID.randomUUID(), "WH-1", type, body, 0);
    }

    private static final String BILLING = ",\"billing_info\":{\"next_billing_time\":\"2026-12-03T17:00:00Z\"}";

    @Test
    void anActiveReportStoresTheTimePayPalGave() {
        when(subscriptions.status(eq("I-SAVE"), eq("ACTIVE"), any())).thenReturn(true);

        normalizer.handle(subscriptionEvent("BILLING.SUBSCRIPTION.ACTIVATED", "ACTIVE", BILLING));

        verify(subscriptions).nextBillingTime("I-SAVE", NEXT);
    }

    @Test
    void aSuspendedOrEndedSubscriptionHasNoNextCharge() {
        when(subscriptions.status(eq("I-SAVE"), anyString(), any())).thenReturn(true);

        normalizer.handle(subscriptionEvent("BILLING.SUBSCRIPTION.SUSPENDED", "SUSPENDED", BILLING));
        normalizer.handle(subscriptionEvent("BILLING.SUBSCRIPTION.CANCELLED", "CANCELLED", BILLING));

        verify(subscriptions, org.mockito.Mockito.times(2)).nextBillingTime("I-SAVE", null);
    }

    @Test
    void aReportThatWasOlderThanTheStoredStateChangesNothing() {
        when(subscriptions.status(eq("I-SAVE"), anyString(), any())).thenReturn(false);

        normalizer.handle(subscriptionEvent("BILLING.SUBSCRIPTION.ACTIVATED", "ACTIVE", BILLING));

        verify(subscriptions, never()).nextBillingTime(anyString(), any());
    }

    @Test
    void anActiveReportWithoutATimeKeepsTheOneStoredAndAnUnreadableTimeIsIgnored() {
        when(subscriptions.status(eq("I-SAVE"), anyString(), any())).thenReturn(true);

        normalizer.handle(subscriptionEvent("BILLING.SUBSCRIPTION.UPDATED", "ACTIVE", ""));
        normalizer.handle(subscriptionEvent("BILLING.SUBSCRIPTION.UPDATED", "ACTIVE",
                ",\"billing_info\":{\"next_billing_time\":\"next tuesday\"}"));

        verify(subscriptions, never()).nextBillingTime(anyString(), any());
    }

    private static InboxEvent sale() {
        String body = "{\"id\":\"WH-2\",\"event_type\":\"PAYMENT.SALE.COMPLETED\",\"resource\":{\"id\":\"SALE-1\","
                + "\"billing_agreement_id\":\"I-SAVE\",\"amount\":{\"total\":\"25.00\",\"currency\":\"USD\"},"
                + "\"transaction_fee\":{\"value\":\"0.90\",\"currency\":\"USD\"},\"create_time\":\"2026-11-10T10:00:00Z\"}}";
        return new InboxEvent(UUID.randomUUID(), "WH-2", "PAYMENT.SALE.COMPLETED", body, 0);
    }

    @Test
    void aDepositSaleRefreshesTheTimeFromPayPal() {
        when(paypal.getSubscription("I-SAVE")).thenReturn(new Subscription("I-SAVE", "ACTIVE", 25,
                Optional.of(Instant.parse("2027-01-03T17:00:00Z")), Optional.empty(), Optional.empty()));

        assertThat(normalizer.handle(sale())).isInstanceOf(Handled.class);

        assertThat(published).singleElement().isInstanceOf(SavingsDepositReceived.class);
        verify(subscriptions).nextBillingTime("I-SAVE", Instant.parse("2027-01-03T17:00:00Z"));
    }

    @Test
    void aDepositIsStillAnnouncedWhenPayPalCannotBeAsked() {
        when(paypal.getSubscription("I-SAVE")).thenThrow(new PayPalException(PayPalException.Kind.RETRYABLE, 503, "UNAVAILABLE", "down", null));

        assertThat(normalizer.handle(sale())).isInstanceOf(Handled.class);

        assertThat(published).singleElement().isInstanceOf(SavingsDepositReceived.class);
        verify(subscriptions, never()).nextBillingTime(anyString(), any());
    }

    @Test
    void aSubscriptionPayPalNoLongerCallsActiveGetsNoTime() {
        when(paypal.getSubscription("I-SAVE")).thenReturn(new Subscription("I-SAVE", "SUSPENDED", 25,
                Optional.of(NEXT), Optional.empty(), Optional.empty()));

        normalizer.handle(sale());

        verify(subscriptions, never()).nextBillingTime(anyString(), any());
    }
}
