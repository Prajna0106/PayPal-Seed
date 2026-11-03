package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsSubscriptionSync;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsDepositReceived;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsSubscriptionState;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsSubscriptionStateChanged;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.Subscription;
import com.paypalseed.payments.application.ports.PayPalGateway.SubscriptionTransaction;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository.SavingsSubscription;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Pulls what a webhook would have pushed (ADR 0028). A machine PayPal cannot call, or a delivery that was lost, leaves a
 * goal waiting for an approval and a deposit that Seed never hears of; this reads the subscription and its recent sales
 * from PayPal and turns them into the very facts {@link WebhookNormalizer} publishes: a state report (ACTIVE, SUSPENDED,
 * CANCELLED, EXPIRED) and one deposit per COMPLETED sale. The caller applies them through the same use cases as the
 * listeners, which are idempotent on the subscription's state time and the PayPal transaction id, so a webhook that comes
 * later, or twice, or before, changes nothing. Nothing is published from here, so a read that finds nothing new leaves
 * no trace.
 */
@Service
class SavingsSyncService {

    /** The longest a subscription's sales are looked back over when no start is known: a month and a few days. */
    static final Duration LOOKBACK = Duration.ofDays(35);

    private final PayPalGateway paypal;
    private final SavingsSubscriptionRepository subscriptions;
    private final Clock realTime;

    @Autowired
    SavingsSyncService(PayPalGateway paypal, SavingsSubscriptionRepository subscriptions, ObjectProvider<Clock> realTime) {
        this(paypal, subscriptions, realTime.getIfAvailable(Clock::systemUTC));
    }

    SavingsSyncService(PayPalGateway paypal, SavingsSubscriptionRepository subscriptions, Clock realTime) {
        this.paypal = paypal;
        this.subscriptions = subscriptions;
        this.realTime = realTime;
    }

    Optional<SavingsSubscriptionSync> sync(UUID goalId) {
        Optional<SavingsSubscription> found = subscriptions.latestByGoal(goalId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SavingsSubscription row = found.get();
        String id = row.subscriptionId();
        Instant now = realTime.instant();
        Subscription current;
        List<SubscriptionTransaction> sales;
        try {
            current = paypal.getSubscription(id);
            sales = paypal.subscriptionTransactions(id, now.minus(LOOKBACK), now);
        } catch (PayPalException e) {
            if (e.isTransient()) {
                throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal is unavailable; try again");
            }
            throw new DomainException(PaymentsErrors.PAYPAL_REJECTED, "PayPal refused the subscription read",
                    e.paypalIssue() == null ? Map.of() : Map.of("paypalIssue", e.paypalIssue()));
        }

        // PayPal's own time orders the report. Without one, the report is dated by the stored status (so it can neither
        // overtake a newer report nor lose to an older one); with nothing stored either, by now.
        Instant reportedAt = current.statusChangedAt().orElseGet(() -> subscriptions.statusAt(id).orElse(now));
        String status = current.status();
        if (subscriptions.status(id, status, reportedAt)) {
            storeNextBilling(id, status, current.nextBillingTime());
        }

        Optional<SavingsSubscriptionStateChanged> state = state(status).map(s -> new SavingsSubscriptionStateChanged(
                Ids.newId(), row.goalId(), row.memberId(), id, s, reportedAt, causedBy(id)));
        List<SavingsDepositReceived> deposits = new ArrayList<>();
        for (SubscriptionTransaction sale : sales) {
            if ("COMPLETED".equals(sale.status()) && sale.grossCents() > 0) {
                deposits.add(new SavingsDepositReceived(Ids.newId(), row.goalId(), row.memberId(), id, sale.id(),
                        sale.grossCents(), sale.feeCents(), sale.time(), causedBy(id)));
            }
        }
        return Optional.of(new SavingsSubscriptionSync(id, status, state, deposits));
    }

    /** The same rule as a webhook: a subscription that is not active has no next charge; an active one keeps the stored time if PayPal gives none. */
    private void storeNextBilling(String subscriptionId, String status, Optional<Instant> reported) {
        if (!"ACTIVE".equals(status)) {
            subscriptions.nextBillingTime(subscriptionId, null);
        } else {
            reported.ifPresent(t -> subscriptions.nextBillingTime(subscriptionId, t));
        }
    }

    /** PayPal's other states (APPROVAL_PENDING, APPROVED, CREATED) change nothing the goal cares about, like CREATED and UPDATED webhooks. */
    private static Optional<SavingsSubscriptionState> state(String status) {
        return switch (status) {
            case "ACTIVE" -> Optional.of(SavingsSubscriptionState.ACTIVE);
            case "SUSPENDED" -> Optional.of(SavingsSubscriptionState.SUSPENDED);
            case "CANCELLED" -> Optional.of(SavingsSubscriptionState.CANCELLED);
            case "EXPIRED" -> Optional.of(SavingsSubscriptionState.EXPIRED);
            default -> Optional.empty();
        };
    }

    private static String causedBy(String subscriptionId) {
        return "paypal-sync:" + subscriptionId;
    }
}
