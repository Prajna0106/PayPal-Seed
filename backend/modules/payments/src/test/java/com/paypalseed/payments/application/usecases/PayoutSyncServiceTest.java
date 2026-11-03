package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutSyncSummary;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.PayoutItem;
import com.paypalseed.payments.application.usecases.PayoutFacts.Report;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository.Payout;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** When a payout is read from PayPal, which id it is read by, and what an answer becomes (ADR 0029). */
class PayoutSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Duration GAP = Duration.ofSeconds(5);

    private final PayPalGateway paypal = mock(PayPalGateway.class);
    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final PayoutSyncStep step = mock(PayoutSyncStep.class);
    private final PayoutSyncService sync = new PayoutSyncService(paypal, payouts, step, Clock.fixed(NOW, ZoneOffset.UTC), GAP,
            Duration.ofSeconds(20));

    private static Payout payout(PayoutStatus status, String batchId, String itemId) {
        return new Payout(UUID.randomUUID(), "ref", "seed:ref", 5_000, RecipientType.EMAIL, "m@example.test", PayoutWallet.PAYPAL,
                null, status, batchId, itemId, null, 0, null);
    }

    private static PayoutItem item(String transactionStatus) {
        return new PayoutItem("BATCH", "UNKNOWN", Optional.of("ITEM"), Optional.of(transactionStatus), Optional.of("TXN"), 25,
                Optional.empty(), Optional.empty());
    }

    private Payout stored(Payout p) {
        when(payouts.byId(p.id())).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void anOpenPayoutIsReadByItsItemAndTheReportIsApplied() {
        Payout p = stored(payout(PayoutStatus.PENDING, "BATCH", "ITEM"));
        when(payouts.claimSync(p.id(), NOW, GAP)).thenReturn(true);
        PayoutItem answer = item("SUCCESS");
        when(paypal.getPayoutItem("ITEM")).thenReturn(answer);
        when(step.apply(p.id(), answer)).thenReturn(payout(PayoutStatus.SUCCEEDED, "BATCH", "ITEM"));

        PayoutSyncService.Outcome outcome = sync.sync(p.id());

        assertThat(outcome.read()).isTrue();
        assertThat(outcome.view().status()).isEqualTo(PayoutStatus.SUCCEEDED);
        verify(paypal, never()).getPayoutBatch(anyString());
    }

    @Test
    void withoutAnItemIdThePayoutIsReadByItsBatch() {
        Payout p = stored(payout(PayoutStatus.REQUESTED, "BATCH", null));
        when(payouts.claimSync(p.id(), NOW, GAP)).thenReturn(true);
        PayoutItem answer = item("PENDING");
        when(paypal.getPayoutBatch("BATCH")).thenReturn(answer);
        when(step.apply(p.id(), answer)).thenReturn(payout(PayoutStatus.PENDING, "BATCH", "ITEM"));

        assertThat(sync.sync(p.id()).read()).isTrue();
        verify(paypal, never()).getPayoutItem(anyString());
    }

    @Test
    void aReadInsideTheMinimumGapAsksPayPalNothing() {
        Payout p = stored(payout(PayoutStatus.PENDING, "BATCH", "ITEM"));
        when(payouts.claimSync(p.id(), NOW, GAP)).thenReturn(false);

        PayoutSyncService.Outcome outcome = sync.sync(p.id());

        assertThat(outcome.read()).isFalse();
        assertThat(outcome.view().status()).isEqualTo(PayoutStatus.PENDING);
        verify(paypal, never()).getPayoutItem(anyString());
        verify(step, never()).apply(any(), any());
    }

    @Test
    void aFinalPayoutIsNeverReadAndTheClaimIsNotTaken() {
        for (PayoutStatus done : List.of(PayoutStatus.FAILED, PayoutStatus.BLOCKED, PayoutStatus.RETURNED)) {
            Payout p = stored(payout(done, "BATCH", "ITEM"));
            assertThat(sync.sync(p.id()).read()).as(done.name()).isFalse();
            verify(payouts, never()).claimSync(eq(p.id()), any(), any());
        }
        verify(paypal, never()).getPayoutItem(anyString());
    }

    @Test
    void aDeliveredPayoutIsReadWhenAskedForBecausePayPalCanStillTakeItBack() {
        Payout p = stored(payout(PayoutStatus.SUCCEEDED, "BATCH", "ITEM"));
        when(payouts.claimSync(p.id(), NOW, GAP)).thenReturn(true);
        PayoutItem answer = item("RETURNED");
        when(paypal.getPayoutItem("ITEM")).thenReturn(answer);
        when(step.apply(p.id(), answer)).thenReturn(payout(PayoutStatus.RETURNED, "BATCH", "ITEM"));

        assertThat(sync.sync(p.id()).view().status()).isEqualTo(PayoutStatus.RETURNED);
    }

    @Test
    void aPayoutPayPalHasGivenNoIdIsLeftAlone() {
        Payout p = stored(payout(PayoutStatus.REQUESTED, null, null));

        assertThat(sync.sync(p.id()).read()).isFalse();
        verify(payouts, never()).claimSync(eq(p.id()), any(), any());
    }

    @Test
    void anUnknownPayoutIs404() {
        UUID id = UUID.randomUUID();
        when(payouts.byId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sync.sync(id)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PaymentsErrors.PAYOUT_NOT_FOUND));
    }

    @Test
    void payPalBeingDownAndPayPalRefusingAreToldApart() {
        Payout p = stored(payout(PayoutStatus.PENDING, "BATCH", "ITEM"));
        when(payouts.claimSync(p.id(), NOW, GAP)).thenReturn(true);
        when(paypal.getPayoutItem("ITEM")).thenThrow(new PayPalException(PayPalException.Kind.RETRYABLE, 503, null, "down"))
                .thenThrow(new PayPalException(PayPalException.Kind.STATE, 404, "INVALID_RESOURCE_ID", "gone"));

        assertThatThrownBy(() -> sync.sync(p.id())).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.DEPENDENCY_UNAVAILABLE));
        assertThatThrownBy(() -> sync.sync(p.id())).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PaymentsErrors.PAYPAL_REJECTED));
        verify(step, never()).apply(any(), any());
    }

    @Test
    void aBatchSkipsAFailingPayoutAndCountsEachKind() {
        Payout good = stored(payout(PayoutStatus.PENDING, "B1", "I1"));
        Payout bad = stored(payout(PayoutStatus.PENDING, "B2", "I2"));
        Payout recent = stored(payout(PayoutStatus.PENDING, "B3", "I3"));
        when(payouts.dueForSync(NOW.minusSeconds(20), 50)).thenReturn(List.of(bad.id(), good.id(), recent.id()));
        when(payouts.claimSync(eq(good.id()), any(), any())).thenReturn(true);
        when(payouts.claimSync(eq(bad.id()), any(), any())).thenReturn(true);
        when(payouts.claimSync(eq(recent.id()), any(), any())).thenReturn(false);
        when(paypal.getPayoutItem("I2")).thenThrow(new PayPalException(PayPalException.Kind.RETRYABLE, 503, null, "down"));
        when(paypal.getPayoutItem("I1")).thenReturn(item("SUCCESS"));
        when(step.apply(eq(good.id()), any())).thenReturn(payout(PayoutStatus.SUCCEEDED, "B1", "I1"));

        assertThat(sync.syncOpen(50)).isEqualTo(new PayoutSyncSummary(3, 1, 1));
    }

    @Test
    void aReadIsAWebhookReportWithTheSameStatusWords() {
        Report succeeded = Report.of(item("SUCCESS"), PayoutFacts.POLL, null);
        Report denied = Report.of(new PayoutItem("BATCH", "DENIED", Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty()), PayoutFacts.POLL, null);
        Report pending = Report.of(item("PENDING"), PayoutFacts.POLL, null);
        Report unclaimed = Report.of(item("UNCLAIMED"), PayoutFacts.POLL, null);

        assertThat(succeeded.status()).contains(PayoutStatus.SUCCEEDED);
        assertThat(succeeded.transactionId()).isEqualTo("TXN");
        assertThat(denied.status()).contains(PayoutStatus.FAILED);
        assertThat(denied.reason()).isEqualTo("BATCH_DENIED");
        assertThat(pending.status()).contains(PayoutStatus.PENDING);
        assertThat(unclaimed.status()).contains(PayoutStatus.UNCLAIMED);
        assertThat(succeeded.source()).isEqualTo("POLL");
    }
}
