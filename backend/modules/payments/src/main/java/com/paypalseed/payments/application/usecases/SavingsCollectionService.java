package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.CapturedOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CheckoutOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.FundingSource;
import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsSubscriptionView;
import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsTopUp;
import com.paypalseed.payments.api.dto.PaymentsDtos.StartSavingsSubscription;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.Capture;
import com.paypalseed.payments.application.ports.PayPalGateway.CreateSubscription;
import com.paypalseed.payments.application.ports.PayPalGateway.Order;
import com.paypalseed.payments.application.ports.PayPalGateway.Revision;
import com.paypalseed.payments.application.ports.PayPalGateway.Subscription;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository.SavingsSubscription;
import com.paypalseed.payments.infrastructure.persistence.TopUpRepository;
import com.paypalseed.payments.infrastructure.persistence.TopUpRepository.TopUp;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Savings money coming in through PayPal: the monthly subscription on the savings plan, and the one-off
 * Orders top-up (the only route for Venmo, because Subscriptions cannot be funded with it). Which goal the
 * money belongs to travels in {@code custom_id}; crediting the goal is the savings module's job.
 */
@Service
class SavingsCollectionService {

    private record SubscriptionRequest(String planId, UUID memberId, long quantity, String returnUrl, String cancelUrl) {
    }

    private record TopUpRequest(UUID memberId, long amountCents, FundingSource funding) {
    }

    private record ChangeRequest(String subscriptionId, String reason) {
    }

    private record ReviseRequest(String subscriptionId, long quantity) {
    }

    private final PayPalGateway paypal;
    private final PayPalCommandExecutor executor;
    private final PaypalCatalogService catalog;
    private final SavingsSubscriptionRepository subscriptions;
    private final TopUpRepository topUps;
    private final TopUpFacts topUpFacts;
    private final InboxRepository inbox;

    SavingsCollectionService(PayPalGateway paypal, PayPalCommandExecutor executor, PaypalCatalogService catalog,
                             SavingsSubscriptionRepository subscriptions, TopUpRepository topUps, TopUpFacts topUpFacts,
                             InboxRepository inbox) {
        this.paypal = paypal;
        this.executor = executor;
        this.catalog = catalog;
        this.subscriptions = subscriptions;
        this.topUps = topUps;
        this.topUpFacts = topUpFacts;
        this.inbox = inbox;
    }

    @Transactional
    SavingsSubscriptionView createSubscription(StartSavingsSubscription c) {
        Optional<SavingsSubscription> existing = subscriptions.byGoalAndRequest(c.goalId(), c.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), c);
        }
        String planId = catalog.savingsPlanId();
        long quantity = c.monthlyCents() / 100; // the plan charges $1 per unit
        CreateSubscription request = new CreateSubscription(quantity, "goal:" + c.goalId(), null, c.returnUrl(), c.cancelUrl());
        Subscription s = executor.run("CREATE_SAVINGS_SUBSCRIPTION", c.goalId(),
                "ss:" + c.goalId() + ":" + shortHash(c.requestId()),
                new SubscriptionRequest(planId, c.memberId(), quantity, c.returnUrl(), c.cancelUrl()), Subscription.class,
                rid -> paypal.createSubscriptionOnPlan(planId, request, rid));
        SavingsSubscription row = new SavingsSubscription(Ids.newId(), c.goalId(), c.memberId(), c.requestId(), c.monthlyCents(),
                s.id(), s.approveUrl().orElse(null), s.status());
        if (!subscriptions.insert(row)) {
            return replay(subscriptions.byGoalAndRequest(c.goalId(), c.requestId()).orElseThrow(), c);
        }
        inbox.wake("subscription:" + s.id());
        return view(row);
    }

    @Transactional(readOnly = true)
    Optional<SavingsSubscriptionView> subscriptionOf(UUID goalId) {
        return subscriptions.latestByGoal(goalId).map(SavingsCollectionService::view);
    }

    @Transactional
    void suspend(UUID goalId, String requestId, String reason) {
        SavingsSubscription s = locked(goalId);
        if (!isLive(s.status()) || "SUSPENDED".equals(s.status())) {
            return;
        }
        executor.run("SUSPEND_SUBSCRIPTION", goalId, key("ss-suspend", s, requestId), new ChangeRequest(s.subscriptionId(), reason),
                Boolean.class, rid -> {
                    paypal.suspend(s.subscriptionId(), reason, rid);
                    return true;
                });
        subscriptions.status(s.subscriptionId(), "SUSPENDED");
    }

    @Transactional
    void activate(UUID goalId, String requestId, String reason) {
        SavingsSubscription s = locked(goalId);
        if (!isLive(s.status())) {
            throw new DomainException(PaymentsErrors.SAVINGS_SUBSCRIPTION_ENDED, "Goal " + goalId + " needs a new subscription");
        }
        if ("ACTIVE".equals(s.status())) {
            return;
        }
        executor.run("ACTIVATE_SUBSCRIPTION", goalId, key("ss-activate", s, requestId), new ChangeRequest(s.subscriptionId(), reason),
                Boolean.class, rid -> {
                    paypal.activate(s.subscriptionId(), reason, rid);
                    return true;
                });
        subscriptions.status(s.subscriptionId(), "ACTIVE");
    }

    @Transactional
    Optional<String> revise(UUID goalId, long monthlyCents, String requestId) {
        if (monthlyCents < 100 || monthlyCents % 100 != 0) {
            throw new IllegalArgumentException("monthly amount must be a whole number of dollars, at least $1");
        }
        SavingsSubscription s = locked(goalId);
        if (!isLive(s.status())) {
            return Optional.empty();
        }
        long quantity = monthlyCents / 100;
        Revision revision = executor.run("REVISE_SUBSCRIPTION", goalId, key("ss-revise", s, requestId),
                new ReviseRequest(s.subscriptionId(), quantity), Revision.class,
                rid -> paypal.reviseQuantity(s.subscriptionId(), quantity, rid));
        subscriptions.monthlyCents(s.subscriptionId(), monthlyCents);
        return revision.approveUrl();
    }

    @Transactional
    void cancel(UUID goalId, String requestId, String reason) {
        SavingsSubscription s = locked(goalId);
        if (!isLive(s.status())) {
            return;
        }
        executor.run("CANCEL_SUBSCRIPTION", goalId, key("ss-cancel", s, requestId), new ChangeRequest(s.subscriptionId(), reason),
                Boolean.class, rid -> {
                    paypal.cancel(s.subscriptionId(), reason, rid);
                    return true;
                });
        subscriptions.status(s.subscriptionId(), "CANCELLED");
    }

    private SavingsSubscription locked(UUID goalId) {
        return subscriptions.latestByGoalForUpdate(goalId)
                .orElseThrow(() -> new DomainException(PaymentsErrors.SAVINGS_SUBSCRIPTION_NOT_FOUND, "No subscription for goal " + goalId));
    }

    /** Key per subscription and request, so a goal that gets a second subscription never replays the first one's answer. */
    private static String key(String kind, SavingsSubscription s, String requestId) {
        return kind + ":" + s.goalId() + ":" + shortHash(s.subscriptionId() + "|" + requestId);
    }

    private static boolean isLive(String status) {
        return !"CANCELLED".equals(status) && !"EXPIRED".equals(status);
    }

    @Transactional
    CheckoutOrder createTopUp(SavingsTopUp c) {
        String hash = shortHash(c.idempotencyKey());
        PayPalGateway.CreateOrder request = new PayPalGateway.CreateOrder(c.amountCents(), "goal:" + c.goalId() + ":TOPUP",
                "SEED-TOP-" + hash + "-" + c.goalId().toString().substring(0, 8), "Seed savings top-up", c.funding());
        Order order = executor.run("CREATE_TOPUP_ORDER", c.goalId(), "topup:" + c.goalId() + ":" + hash,
                new TopUpRequest(c.memberId(), c.amountCents(), c.funding()), Order.class,
                rid -> paypal.createOrder(request, rid));
        topUps.record(order.id(), c.memberId(), c.goalId(), c.amountCents(), c.funding(), order.status());
        inbox.wake("topup:" + order.id());
        return new CheckoutOrder(order.id(), order.status(), order.approveUrl(), c.amountCents());
    }

    @Transactional
    CapturedOrder captureTopUp(UUID goalId, String orderId) {
        TopUp topUp = topUps.byOrder(orderId).filter(t -> t.goalId().equals(goalId))
                .orElseThrow(() -> new DomainException(PaymentsErrors.TOPUP_NOT_FOUND, "No such top-up"));
        Capture capture = executor.run("CAPTURE_TOPUP_ORDER", goalId, "topup-capture:" + orderId, new CaptureRequest(orderId),
                Capture.class, rid -> paypal.captureOrder(orderId, rid));
        if ("COMPLETED".equals(capture.status())) {
            topUpFacts.captured(topUp, capture.id(), capture.createTime(), "order:" + orderId);
        } else {
            topUps.status(orderId, capture.status()); // PENDING resolves by webhook; anything else is a refusal
        }
        return new CapturedOrder(orderId, capture.id(), capture.status(), capture.amountCents());
    }

    private record CaptureRequest(String orderId) {
    }

    private static SavingsSubscriptionView replay(SavingsSubscription existing, StartSavingsSubscription c) {
        if (!existing.memberId().equals(c.memberId()) || existing.monthlyCents() != c.monthlyCents()) {
            throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED,
                    "Request " + c.requestId() + " was already used for a different member or amount");
        }
        return view(existing);
    }

    private static SavingsSubscriptionView view(SavingsSubscription s) {
        return new SavingsSubscriptionView(s.goalId(), s.subscriptionId(), s.status(), Optional.ofNullable(s.approveUrl()),
                s.monthlyCents());
    }

    /** Keeps {@code PayPal-Request-Id} (108 characters at most) short however long the caller's key is. */
    static String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
