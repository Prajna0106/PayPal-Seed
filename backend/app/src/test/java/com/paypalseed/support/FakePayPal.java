package com.paypalseed.support;

import com.paypalseed.payments.api.dto.PaymentsDtos.FundingSource;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import java.time.Duration;
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
 * call, and lets tests inject failures (503s, timeouts, refusals) per operation. It also simulates the
 * billing catalog, payouts (with a configurable outcome) and a Transaction Search ledger.
 */
public class FakePayPal implements PayPalGateway {

    public static final class Sub {
        public volatile String status = "APPROVAL_PENDING";
        public volatile long quantity;
        public volatile Instant nextBilling;
        public volatile String planId;
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

    // ---- billing catalog ----
    public final Map<String, String> products = new ConcurrentHashMap<>();
    public final Map<String, Plan> plans = new ConcurrentHashMap<>();
    public final Map<String, Long> planUnitPriceCents = new ConcurrentHashMap<>();

    // ---- orders ----
    public final Map<String, FundingSource> orderFunding = new ConcurrentHashMap<>();

    // ---- payouts ----

    /** What {@code createPayout} reports back; later states are reached with {@link #settlePayout}. */
    public enum PayoutOutcome {
        INSTANT_SUCCESS,
        PENDING,
        FAILED,
        BLOCKED,
        UNCLAIMED,
        RETURNED
    }

    public static final class SimPayout {
        public final String batchId;
        public final String itemId;
        public final String senderBatchId;
        public final String senderItemId;
        public final long amountCents;
        public final String receiver;
        public final String recipientType;
        public final String wallet;
        public volatile String status;
        public volatile String errorName;
        public volatile String transactionId;
        public volatile long feeCents;

        SimPayout(String batchId, String itemId, CreatePayout r) {
            this.batchId = batchId;
            this.itemId = itemId;
            this.senderBatchId = r.senderBatchId();
            this.senderItemId = r.senderItemId();
            this.amountCents = r.amountCents();
            this.receiver = r.receiver();
            this.recipientType = r.recipientType().name();
            this.wallet = r.wallet().name();
        }
    }

    /** Identifiers as a webhook would carry them, for a payout this fake never created (early or foreign events). */
    public static SimPayout simPayoutForWebhook(String batchId, String itemId, String senderBatchId, String senderItemId) {
        return new SimPayout(batchId, itemId, new CreatePayout(senderBatchId, senderItemId, RecipientType.EMAIL,
                "member@example.test", PayoutWallet.PAYPAL, 5_000, null));
    }

    public volatile PayoutOutcome payoutOutcome = PayoutOutcome.PENDING;
    public final Map<String, SimPayout> payoutsByBatch = new ConcurrentHashMap<>();

    // ---- transaction search ----
    public final List<SearchedTransaction> transactions = Collections.synchronizedList(new ArrayList<>());
    public final List<SearchTransactions> searches = Collections.synchronizedList(new ArrayList<>());

    /** Clears per-test knobs and the call log; PayPal objects persist, like a real sandbox. */
    public void reset() {
        calls.clear();
        failures.clear();
        raiseNeedsConsent = false;
        feeCents = 0;
        payoutOutcome = PayoutOutcome.PENDING;
        searches.clear();
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
            orderFunding.put(id, request.funding());
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

    // ---- billing catalog ----

    @Override
    public List<Product> listProducts() {
        return op("listProducts", null, () -> products.entrySet().stream().map(e -> new Product(e.getKey(), e.getValue())).toList());
    }

    @Override
    public Product createProduct(String name, String description, String requestId) {
        return op("createProduct", requestId, () -> {
            String id = nextId("PROD");
            products.put(id, name);
            return new Product(id, name);
        });
    }

    @Override
    public List<Plan> listPlans(String productId) {
        return op("listPlans", null, () -> plans.values().stream().filter(p -> p.productId().equals(productId)).toList());
    }

    @Override
    public Plan createPlan(CreatePlan request, String requestId) {
        return op("createPlan", requestId, () -> {
            String id = nextId("P");
            Plan plan = new Plan(id, request.productId(), request.name(), "ACTIVE");
            plans.put(id, plan);
            planUnitPriceCents.put(id, request.unitPriceCents());
            return plan;
        });
    }

    @Override
    public Subscription createSubscriptionOnPlan(String planId, CreateSubscription r, String requestId) {
        return op("subscribeOnPlan", requestId, () -> {
            String id = nextId("I-SUB");
            Sub sub = new Sub(r.quantity(), r.startTime(), r.customId());
            sub.planId = planId;
            subscriptions.put(id, sub);
            return view(id, Optional.of("https://sandbox.paypal.test/approve/" + id));
        });
    }

    // ---- payouts ----

    @Override
    public PayoutItem createPayout(CreatePayout request, String requestId) {
        return op("createPayout", requestId, () -> {
            boolean reused = payoutsByBatch.values().stream().anyMatch(p -> p.senderBatchId.equals(request.senderBatchId()));
            if (reused) {
                throw new PayPalException(PayPalException.Kind.FINAL, 400, "SENDER_BATCH_ID_REUSED", "PayPal 400 sender_batch_id reused");
            }
            SimPayout p = new SimPayout(nextId("BATCH"), nextId("ITEM"), request);
            switch (payoutOutcome) {
                case INSTANT_SUCCESS -> succeed(p);
                case PENDING -> p.status = "PENDING";
                case FAILED -> fail(p, "FAILED", "RECEIVER_UNREGISTERED");
                case BLOCKED -> fail(p, "BLOCKED", "RECEIVER_BLOCKED");
                case UNCLAIMED -> p.status = "UNCLAIMED";
                case RETURNED -> p.status = "RETURNED";
            }
            payoutsByBatch.put(p.batchId, p);
            return payoutItem(p);
        });
    }

    @Override
    public PayoutItem getPayoutItem(String payoutItemId) {
        return op("getPayoutItem", null, () -> payoutItem(payoutByItem(payoutItemId)));
    }

    @Override
    public PayoutItem getPayoutBatch(String payoutBatchId) {
        return op("getPayoutBatch", null, () -> {
            SimPayout p = payoutsByBatch.get(payoutBatchId);
            if (p == null) {
                throw new PayPalException(PayPalException.Kind.STATE, 404, "INVALID_RESOURCE_ID", "no payout batch " + payoutBatchId);
            }
            return payoutItem(p);
        });
    }

    /** Moves a payout on at PayPal (what a later webhook would then announce). */
    public void settlePayout(String payoutItemId, String transactionStatus, String errorName) {
        SimPayout p = payoutByItem(payoutItemId);
        if ("SUCCESS".equals(transactionStatus)) {
            succeed(p);
        } else {
            p.status = transactionStatus;
            p.errorName = errorName;
        }
    }

    public SimPayout payoutOfReference(String referenceId) {
        return payoutsByBatch.values().stream().filter(p -> p.senderItemId.equals(referenceId)).findFirst().orElseThrow();
    }

    private SimPayout payoutByItem(String itemId) {
        return payoutsByBatch.values().stream().filter(p -> p.itemId.equals(itemId)).findFirst()
                .orElseThrow(() -> new PayPalException(PayPalException.Kind.STATE, 404, "INVALID_RESOURCE_ID", "no payout item " + itemId));
    }

    private void succeed(SimPayout p) {
        p.status = "SUCCESS";
        p.transactionId = nextId("TXN");
        p.feeCents = 25;
    }

    private static void fail(SimPayout p, String status, String error) {
        p.status = status;
        p.errorName = error;
    }

    private static PayoutItem payoutItem(SimPayout p) {
        String batchStatus = "PENDING".equals(p.status) ? "PENDING" : "SUCCESS";
        return new PayoutItem(p.batchId, batchStatus, Optional.of(p.itemId), Optional.of(p.status),
                Optional.ofNullable(p.transactionId), p.feeCents, Optional.ofNullable(p.errorName),
                Optional.ofNullable(p.errorName).map(n -> "simulated " + n));
    }

    // ---- transaction search ----

    @Override
    public List<SearchedTransaction> searchTransactions(SearchTransactions query) {
        return op("searchTransactions", null, () -> {
            if (Duration.between(query.from(), query.to()).compareTo(Duration.ofDays(31)) > 0) {
                throw new PayPalException(PayPalException.Kind.FINAL, 400, "INVALID_REQUEST", "PayPal 400 window over 31 days");
            }
            searches.add(query);
            return transactions.stream()
                    .filter(t -> !t.initiatedAt().isBefore(query.from()) && t.initiatedAt().isBefore(query.to())).toList();
        });
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
