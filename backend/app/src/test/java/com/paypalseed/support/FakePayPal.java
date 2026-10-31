package com.paypalseed.support;

import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A stateful PayPal sandbox stand-in. It keeps subscriptions, orders and captures, honours
 * {@code PayPal-Request-Id} idempotency the way PayPal does (same id, same result), records every
 * call, and lets tests inject failures (503s, timeouts, refusals) per operation.
 */
public class FakePayPal implements PayPalGateway {

    public static final class Sub {
        public volatile String status = "APPROVAL_PENDING";
        public volatile long quantity;
        public volatile Instant nextBilling;
        public final String customId;
        public final List<SubscriptionTransaction> transactions = Collections.synchronizedList(new ArrayList<>());

        Sub(long quantity, Instant nextBilling, String customId) {
            this.quantity = quantity;
            this.nextBilling = nextBilling;
            this.customId = customId;
        }
    }

    public final Map<String, Sub> subscriptions = new ConcurrentHashMap<>();
    public final Map<String, Long> orders = new ConcurrentHashMap<>();
    public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> byRequestId = new ConcurrentHashMap<>();
    private final Map<String, List<PayPalException>> failures = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    public volatile boolean raiseNeedsConsent;
    public volatile long feeCents = 0;

    /** Clears per-test knobs and the call log; PayPal objects persist, like a real sandbox. */
    public void reset() {
        calls.clear();
        failures.clear();
        raiseNeedsConsent = false;
        feeCents = 0;
    }

    /** The next call(s) of {@code operation} fail with these errors, in order. */
    public void failNext(String operation, PayPalException... errors) {
        failures.computeIfAbsent(operation, k -> Collections.synchronizedList(new ArrayList<>())).addAll(List.of(errors));
    }

    public static PayPalException unavailable() {
        return new PayPalException(PayPalException.Kind.RETRYABLE, 503, "SERVICE_UNAVAILABLE", "PayPal 503");
    }

    public static PayPalException refused(String issue) {
        return new PayPalException(PayPalException.Kind.STATE, 422, issue, "PayPal 422 " + issue);
    }

    public String nextId(String prefix) {
        return prefix + "-" + ids.incrementAndGet();
    }

    /** Member approved the subscription in the PayPal popup. */
    public void approve(String subscriptionId) {
        subscriptions.get(subscriptionId).status = "ACTIVE";
    }

    public long callCount(String operation) {
        return calls.stream().filter(c -> c.startsWith(operation)).count();
    }

    @SuppressWarnings("unchecked")
    private <T> T op(String operation, String requestId, Supplier<T> action) {
        calls.add(operation + (requestId == null ? "" : " " + requestId));
        List<PayPalException> queued = failures.get(operation);
        if (queued != null && !queued.isEmpty()) {
            throw queued.remove(0);
        }
        if (requestId == null) {
            return action.get();
        }
        return (T) byRequestId.computeIfAbsent(operation + "|" + requestId, k -> action.get());
    }

    @Override
    public Subscription createSubscription(CreateSubscription r, String requestId) {
        return op("createSubscription", requestId, () -> {
            String id = nextId("I-SUB");
            subscriptions.put(id, new Sub(r.quantity(), r.startTime(), r.customId()));
            return view(id, Optional.of("https://sandbox.paypal.test/approve/" + id));
        });
    }

    @Override
    public Subscription getSubscription(String subscriptionId) {
        return op("getSubscription", null, () -> view(subscriptionId, Optional.empty()));
    }

    @Override
    public Revision reviseQuantity(String subscriptionId, long quantity, String requestId) {
        return op("revise", requestId, () -> {
            Sub s = subscriptions.get(subscriptionId);
            if (raiseNeedsConsent && quantity > s.quantity) {
                return new Revision(Optional.of("https://sandbox.paypal.test/consent/" + subscriptionId));
            }
            s.quantity = quantity;
            return new Revision(Optional.empty());
        });
    }

    @Override
    public void suspend(String subscriptionId, String reason, String requestId) {
        op("suspend", requestId, () -> subscriptions.get(subscriptionId).status = "SUSPENDED");
    }

    @Override
    public void activate(String subscriptionId, String reason, String requestId) {
        op("activate", requestId, () -> subscriptions.get(subscriptionId).status = "ACTIVE");
    }

    @Override
    public void cancel(String subscriptionId, String reason, String requestId) {
        op("cancel", requestId, () -> subscriptions.get(subscriptionId).status = "CANCELLED");
    }

    @Override
    public Capture captureOutstanding(String subscriptionId, long amountCents, String note, String requestId) {
        return op("captureOutstanding", requestId, () -> {
            String id = nextId("SALE");
            subscriptions.get(subscriptionId).transactions
                    .add(new SubscriptionTransaction(id, "COMPLETED", amountCents, feeCents, Instant.now()));
            return new Capture(id, "COMPLETED", amountCents, feeCents, Instant.now());
        });
    }

    @Override
    public List<SubscriptionTransaction> subscriptionTransactions(String subscriptionId, Instant from, Instant to) {
        return op("subscriptionTransactions", null, () -> {
            view(subscriptionId, Optional.empty()); // 404 for an unknown subscription, as PayPal does
            return List.copyOf(subscriptions.get(subscriptionId).transactions);
        });
    }

    @Override
    public Order createOrder(CreateOrder request, String requestId) {
        return op("createOrder", requestId, () -> {
            String id = nextId("ORDER");
            orders.put(id, request.amountCents());
            return new Order(id, "CREATED", Optional.of("https://sandbox.paypal.test/checkout/" + id));
        });
    }

    @Override
    public Capture captureOrder(String orderId, String requestId) {
        return op("captureOrder", requestId,
                () -> new Capture(nextId("CAP"), "COMPLETED", orders.get(orderId), feeCents, Instant.now()));
    }

    @Override
    public Refund refundCapture(String captureId, long amountCents, String invoiceId, String requestId) {
        return op("refundCapture", requestId, () -> new Refund(nextId("REFUND"), "COMPLETED", amountCents));
    }

    @Override
    public Refund refundSale(String saleId, long amountCents, String requestId) {
        return op("refundSale", requestId, () -> new Refund(nextId("REFUND"), "completed", amountCents));
    }

    @Override
    public boolean verifyWebhook(Map<String, String> headers, String rawBody) {
        return !"bad".equals(headers.get("paypal-transmission-sig"));
    }

    private Subscription view(String id, Optional<String> approveUrl) {
        Sub s = subscriptions.get(id);
        if (s == null) {
            throw new PayPalException(PayPalException.Kind.STATE, 404, "RESOURCE_NOT_FOUND", "no subscription " + id);
        }
        return new Subscription(id, s.status, s.quantity, Optional.ofNullable(s.nextBilling), approveUrl,
                Optional.of(s.customId));
    }
}
