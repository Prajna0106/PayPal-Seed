package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsSubscriptionSync;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsDepositReceived;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsSubscriptionState;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.Subscription;
import com.paypalseed.payments.application.ports.PayPalGateway.SubscriptionTransaction;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository.SavingsSubscription;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a read of PayPal becomes: the same state report and deposit facts the webhooks announce (ADR 0028). */
class SavingsSyncServiceTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Instant NEXT = Instant.parse("2026-12-10T17:00:00Z");

    private final PayPalGateway paypal = mock(PayPalGateway.class);
    private final SavingsSubscriptionRepository subscriptions = mock(SavingsSubscriptionRepository.class);
    private final SavingsSyncService sync = new SavingsSyncService(paypal, subscriptions, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void theGoalHasASubscription() {
        when(subscriptions.latestByGoal(GOAL)).thenReturn(Optional.of(
                new SavingsSubscription(UUID.randomUUID(), GOAL, MEMBER, "req", 2_500, "I-SAVE", null, "APPROVAL_PENDING")));
        when(subscriptions.status(eq("I-SAVE"), any(), any())).thenReturn(true);
        when(subscriptions.statusAt("I-SAVE")).thenReturn(Optional.empty());
        when(paypal.subscriptionTransactions(any(), any(), any())).thenReturn(List.of());
    }

    private void paypalSays(String status, Instant changedAt, Instant nextBilling) {
        when(paypal.getSubscription("I-SAVE")).thenReturn(new Subscription("I-SAVE", status, 25,
                Optional.ofNullable(nextBilling), Optional.empty(), Optional.of("goal:" + GOAL), Optional.ofNullable(changedAt)));
    }

    private static SubscriptionTransaction sale(String id, String status, long gross) {
        return new SubscriptionTransaction(id, status, gross, 90, Instant.parse("2026-11-10T11:00:00Z"));
    }

    @Test
    void anActiveSubscriptionIsAnActiveReportDatedByPayPalsOwnTime() {
        Instant approved = Instant.parse("2026-11-10T11:30:00Z");
        paypalSays("ACTIVE", approved, NEXT);

        SavingsSubscriptionSync result = sync.sync(GOAL).orElseThrow();

        assertThat(result.subscriptionId()).isEqualTo("I-SAVE");
        assertThat(result.state()).hasValueSatisfying(s -> {
            assertThat(s.state()).isEqualTo(SavingsSubscriptionState.ACTIVE);
            assertThat(s.occurredAt()).isEqualTo(approved);
            assertThat(s.goalId()).isEqualTo(GOAL);
            assertThat(s.memberId()).isEqualTo(MEMBER);
        });
        verify(subscriptions).status("I-SAVE", "ACTIVE", approved);
        verify(subscriptions).nextBillingTime("I-SAVE", NEXT);
    }

    @Test
    void eachEndedStateIsReportedAsItsOwnState() {
        for (String status : List.of("SUSPENDED", "CANCELLED", "EXPIRED")) {
            paypalSays(status, NOW, null);
            assertThat(sync.sync(GOAL).orElseThrow().state().orElseThrow().state().name()).isEqualTo(status);
        }
    }

    @Test
    void aSubscriptionNotApprovedYetReportsNoStateAndKeepsNoNextCharge() {
        paypalSays("APPROVAL_PENDING", null, null);

        SavingsSubscriptionSync result = sync.sync(GOAL).orElseThrow();

        assertThat(result.state()).isEmpty();
        assertThat(result.paypalStatus()).isEqualTo("APPROVAL_PENDING");
        verify(subscriptions).nextBillingTime("I-SAVE", null);
    }

    @Test
    void withNoTimeFromPayPalTheStoredStatusTimeDatesTheReportAndNothingStoredMeansNow() {
        Instant stored = Instant.parse("2026-11-09T08:00:00Z");
        when(subscriptions.statusAt("I-SAVE")).thenReturn(Optional.of(stored));
        paypalSays("SUSPENDED", null, null);
        assertThat(sync.sync(GOAL).orElseThrow().state().orElseThrow().occurredAt()).isEqualTo(stored);

        when(subscriptions.statusAt("I-SAVE")).thenReturn(Optional.empty());
        assertThat(sync.sync(GOAL).orElseThrow().state().orElseThrow().occurredAt()).isEqualTo(NOW);
    }

    @Test
    void aReportTheStoredStatusRefusesAsOlderStillGoesOnButChangesNoNextCharge() {
        when(subscriptions.status(eq("I-SAVE"), any(), any())).thenReturn(false);
        paypalSays("ACTIVE", Instant.parse("2026-11-01T00:00:00Z"), NEXT);

        SavingsSubscriptionSync result = sync.sync(GOAL).orElseThrow();

        assertThat(result.state()).as("the goal does its own ordering").isPresent();
        verify(subscriptions, never()).nextBillingTime(any(), any());
    }

    @Test
    void anActiveSubscriptionWithNoNextChargeInTheReportKeepsTheStoredOne() {
        paypalSays("ACTIVE", NOW, null);

        sync.sync(GOAL);

        verify(subscriptions, never()).nextBillingTime(any(), any());
    }

    @Test
    void oneDepositPerCompletedSaleCarryingThePayPalTransactionIdAndTheGrossAmount() {
        paypalSays("ACTIVE", NOW, NEXT);
        when(paypal.subscriptionTransactions(eq("I-SAVE"), any(), any())).thenReturn(List.of(
                sale("SALE-1", "COMPLETED", 2_500), sale("SALE-2", "PENDING", 2_500), sale("SALE-3", "DENIED", 2_500),
                sale("SALE-4", "COMPLETED", 3_000)));

        List<SavingsDepositReceived> deposits = sync.sync(GOAL).orElseThrow().deposits();

        assertThat(deposits).extracting(SavingsDepositReceived::paypalTxnId).containsExactly("SALE-1", "SALE-4");
        assertThat(deposits).extracting(SavingsDepositReceived::amountCents).containsExactly(2_500L, 3_000L);
        assertThat(deposits).allSatisfy(d -> {
            assertThat(d.goalId()).isEqualTo(GOAL);
            assertThat(d.memberId()).isEqualTo(MEMBER);
            assertThat(d.subscriptionId()).isEqualTo("I-SAVE");
            assertThat(d.feeCents()).isEqualTo(90);
            assertThat(d.occurredAt()).isEqualTo(Instant.parse("2026-11-10T11:00:00Z"));
        });
    }

    @Test
    void salesAreReadOverTheLastThirtyFiveDaysUpToNow() {
        paypalSays("ACTIVE", NOW, NEXT);

        sync.sync(GOAL);

        verify(paypal).subscriptionTransactions("I-SAVE", NOW.minus(Duration.ofDays(35)), NOW);
    }

    @Test
    void aGoalWithNoSubscriptionIsNotReadAtAll() {
        UUID other = UUID.randomUUID();
        when(subscriptions.latestByGoal(other)).thenReturn(Optional.empty());

        assertThat(sync.sync(other)).isEmpty();
        verify(paypal, never()).getSubscription(any());
    }

    @Test
    void aPayPalOutageIsDependencyUnavailableAndNothingIsStored() {
        when(paypal.getSubscription("I-SAVE")).thenThrow(new PayPalException(PayPalException.Kind.RETRYABLE, 503, "UNAVAILABLE", "down"));

        assertThatThrownBy(() -> sync.sync(GOAL)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.DEPENDENCY_UNAVAILABLE));
        verify(subscriptions, never()).status(any(), any(), any());
    }

    @Test
    void aSubscriptionPayPalRefusesToShowIsPaypalRejected() {
        when(paypal.getSubscription("I-SAVE")).thenThrow(new PayPalException(PayPalException.Kind.STATE, 404, "RESOURCE_NOT_FOUND", "gone"));

        assertThatThrownBy(() -> sync.sync(GOAL)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PaymentsErrors.PAYPAL_REJECTED));
    }
}
