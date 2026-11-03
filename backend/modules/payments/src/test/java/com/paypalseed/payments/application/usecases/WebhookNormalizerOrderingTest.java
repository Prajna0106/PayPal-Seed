package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsSubscriptionStateChanged;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository.InboxEvent;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository;
import com.paypalseed.payments.infrastructure.persistence.MoneyRegistry;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository.SavingsSubscription;
import com.paypalseed.payments.infrastructure.persistence.TopUpRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * How a savings subscription report is dated when PayPal leaves out the time that dates it (ADR 0027). The savings module
 * never applies a report older than the state it already knows, so the date decides the order: the resource's
 * {@code update_time}, else the webhook's {@code create_time}, else the order in which the webhooks were received.
 */
class WebhookNormalizerOrderingTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Instant CLOCK_NOW = Instant.parse("2026-11-20T00:00:00Z");

    private final SavingsSubscriptionRepository subscriptions = mock(SavingsSubscriptionRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher events = published::add;
    private final WebhookNormalizer normalizer = new WebhookNormalizer(new ObjectMapper(), mock(MandateRepository.class),
            mock(MoneyRegistry.class), mock(MoneyFacts.class), events, mock(PayPalGateway.class), mock(PayoutRepository.class),
            mock(PayoutFacts.class), subscriptions, mock(TopUpRepository.class), mock(TopUpFacts.class),
            Clock.fixed(CLOCK_NOW, ZoneOffset.UTC));

    WebhookNormalizerOrderingTest() {
        when(subscriptions.bySubscription("I-SAVE")).thenReturn(Optional.of(
                new SavingsSubscription(UUID.randomUUID(), GOAL, MEMBER, "req", 2_500, "I-SAVE", null, "ACTIVE")));
    }

    private static String resource(String type, String times) {
        String status = type.endsWith("SUSPENDED") ? "SUSPENDED" : "ACTIVE";
        return "{\"id\":\"I-SAVE\",\"status\":\"" + status + "\",\"custom_id\":\"goal:" + GOAL + "\"" + times + "}";
    }

    private Instant dated(String type, String webhookCreateTime, String resourceTimes, Instant receivedAt) {
        String eventTime = webhookCreateTime == null ? "" : ",\"create_time\":\"" + webhookCreateTime + "\"";
        String body = "{\"id\":\"WH-1\",\"event_type\":\"" + type + "\"" + eventTime + ",\"resource\":" + resource(type, resourceTimes) + "}";
        published.clear();
        normalizer.handle(new InboxEvent(UUID.randomUUID(), "WH-" + UUID.randomUUID(), type, body, 0, receivedAt));
        return ((SavingsSubscriptionStateChanged) published.getFirst()).occurredAt();
    }

    @Test
    void theResourceUpdateTimeComesFirst() {
        Instant at = dated("BILLING.SUBSCRIPTION.SUSPENDED", "2026-11-12T09:00:05Z", ",\"update_time\":\"2026-11-12T09:00:00Z\"",
                Instant.parse("2026-11-12T09:00:09Z"));

        assertThat(at).isEqualTo(Instant.parse("2026-11-12T09:00:00Z"));
    }

    @Test
    void withoutAnUpdateTimeTheWebhookCreateTimeDatesIt() {
        Instant at = dated("BILLING.SUBSCRIPTION.SUSPENDED", "2026-11-12T09:00:05Z", "", Instant.parse("2026-11-12T09:00:09Z"));

        assertThat(at).isEqualTo(Instant.parse("2026-11-12T09:00:05Z"));
    }

    @Test
    void aMalformedUpdateTimeIsTreatedAsMissing() {
        Instant at = dated("BILLING.SUBSCRIPTION.SUSPENDED", "2026-11-12T09:00:05Z", ",\"update_time\":\"yesterday\"",
                Instant.parse("2026-11-12T09:00:09Z"));

        assertThat(at).isEqualTo(Instant.parse("2026-11-12T09:00:05Z"));
    }

    @Test
    void withNeitherTimeTheOrderOfReceiptDatesIt() {
        Instant at = dated("BILLING.SUBSCRIPTION.SUSPENDED", null, "", Instant.parse("2026-11-12T09:00:09Z"));

        assertThat(at).isEqualTo(Instant.parse("2026-11-12T09:00:09Z"));
    }

    @Test
    void reportsWithNoTimeAreOrderedByReceiptNotByWhenTheyAreProcessed() {
        Instant receivedFirst = Instant.parse("2026-11-12T09:00:00Z");
        Instant receivedSecond = Instant.parse("2026-11-12T09:05:00Z");

        // Processed in the reverse order (the first one had been parked): the dates still follow receipt.
        Instant second = dated("BILLING.SUBSCRIPTION.ACTIVATED", null, "", receivedSecond);
        Instant first = dated("BILLING.SUBSCRIPTION.SUSPENDED", null, "", receivedFirst);

        assertThat(first).isBefore(second);
    }

    @Test
    void aReportNeverStoredFallsBackToTheRealClock() {
        Instant at = dated("BILLING.SUBSCRIPTION.SUSPENDED", null, "", null);

        assertThat(at).isEqualTo(CLOCK_NOW);
    }

    @Test
    void theStoredStatusIsReportedWithTheSameDateSoAnOlderReportCannotMoveItBack() {
        Instant at = dated("BILLING.SUBSCRIPTION.SUSPENDED", "2026-11-12T09:00:05Z", ",\"update_time\":\"2026-11-12T09:00:00Z\"", null);

        verify(subscriptions).status("I-SAVE", "SUSPENDED", at);
    }
}
