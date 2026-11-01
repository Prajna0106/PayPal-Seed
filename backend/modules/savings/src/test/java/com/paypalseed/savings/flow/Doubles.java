package com.paypalseed.savings.flow;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.ledger.api.dto.Side;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.application.ports.CreditedPayments;
import com.paypalseed.savings.application.ports.PayoutRecipients;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.application.ports.WithdrawalStore;
import com.paypalseed.savings.domain.model.SavingsWallet;
import com.paypalseed.savings.domain.model.Withdrawal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** In-memory stand-ins for the ports of the PayPal-backed savings flows. */
final class Doubles {

    private Doubles() {
    }

    static final class Outbox implements EventOutbox {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publish(Object event) {
            events.add(event);
        }

        <T> List<T> of(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    /** Remembers every posting once per source key and sums balances on each account's normal side. */
    static final class Ledger implements LedgerFacade {
        final Map<String, PostingRequest> postings = new LinkedHashMap<>();
        private final Map<AccountRef, Long> balances = new HashMap<>();

        @Override
        public UUID post(PostingRequest request) {
            if (postings.putIfAbsent(request.sourceKey(), request) == null) {
                for (PostingRequest.Line line : request.lines()) {
                    long signed = line.side() == line.account().type().normalSide() ? line.amountCents() : -line.amountCents();
                    balances.merge(line.account(), signed, Long::sum);
                }
            }
            return UUID.nameUUIDFromBytes(request.sourceKey().getBytes());
        }

        @Override
        public long balanceCents(AccountRef account) {
            return balances.getOrDefault(account, 0L);
        }

        /** Every posting is balanced by construction; this asserts the sum of all lines is zero too. */
        long debitsMinusCredits() {
            return postings.values().stream().flatMap(p -> p.lines().stream())
                .mapToLong(l -> l.side() == Side.DEBIT ? l.amountCents() : -l.amountCents()).sum();
        }
    }

    static final class Withdrawals implements WithdrawalStore {
        final Map<UUID, Withdrawal> byId = new HashMap<>();

        @Override
        public boolean insert(Withdrawal w) {
            boolean taken = byId.values().stream()
                .anyMatch(x -> x.getGoalId().equals(w.getGoalId()) && x.getIdempotencyKey().equals(w.getIdempotencyKey()));
            if (taken) {
                return false;
            }
            byId.put(w.getId(), w);
            return true;
        }

        @Override
        public Optional<Withdrawal> findByGoalAndKey(UUID goalId, String key) {
            return byId.values().stream().filter(w -> w.getGoalId().equals(goalId) && w.getIdempotencyKey().equals(key)).findFirst();
        }

        @Override
        public Optional<Withdrawal> findByIdForUpdate(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Withdrawal> findByGoal(UUID goalId) {
            return byId.values().stream().filter(w -> w.getGoalId().equals(goalId))
                .sorted(Comparator.comparing(Withdrawal::getCreatedAt).reversed()).toList();
        }

        @Override
        public void update(Withdrawal w) {
            byId.put(w.getId(), w);
        }
    }

    static final class Credited implements CreditedPayments {
        final Set<String> claimed = new HashSet<>();

        @Override
        public boolean claim(String paypalTxnId, UUID goalId, long amountCents, Source source) {
            return claimed.add(paypalTxnId);
        }
    }

    static final class Recipients implements PayoutRecipients {
        Recipient recipient = new Recipient("PAYER-ANA", "ana@example.com", SavingsWallet.PAYPAL);

        @Override
        public Optional<Recipient> of(UUID memberId) {
            return Optional.ofNullable(recipient);
        }
    }

    /** Records what savings asked of PayPal and answers as a test tells it to. */
    static final class Payments implements SavingsPayments {
        final List<String> calls = new ArrayList<>();
        final Map<UUID, Subscription> subscriptions = new HashMap<>();
        PayoutState nextPayout = PayoutState.PENDING;
        String nextReason;
        RuntimeException failNextPayout;
        int subscriptionsStarted;

        @Override
        public Subscription startSubscription(UUID memberId, UUID goalId, long monthlyCents, String requestId, String returnUrl,
                                              String cancelUrl) {
            calls.add("start:" + goalId + ":" + monthlyCents + ":" + requestId);
            subscriptionsStarted++;
            Subscription s = new Subscription("I-" + subscriptionsStarted, "APPROVAL_PENDING",
                Optional.of("https://paypal.test/approve/I-" + subscriptionsStarted + "?return=" + returnUrl));
            subscriptions.put(goalId, s);
            return s;
        }

        @Override
        public Optional<Subscription> subscriptionOf(UUID goalId) {
            return Optional.ofNullable(subscriptions.get(goalId));
        }

        void setStatus(UUID goalId, String status) {
            Subscription s = subscriptions.get(goalId);
            subscriptions.put(goalId, new Subscription(s.subscriptionId(), status, s.approvalUrl()));
        }

        @Override
        public void suspend(UUID goalId, String requestId, String reason) {
            calls.add("suspend:" + goalId + ":" + requestId);
            setStatus(goalId, "SUSPENDED");
        }

        @Override
        public void activate(UUID goalId, String requestId, String reason) {
            calls.add("activate:" + goalId + ":" + requestId);
            setStatus(goalId, "ACTIVE");
        }

        @Override
        public Optional<String> revise(UUID goalId, long monthlyCents, String requestId) {
            calls.add("revise:" + goalId + ":" + monthlyCents + ":" + requestId);
            return Optional.empty();
        }

        @Override
        public void cancel(UUID goalId, String requestId, String reason) {
            calls.add("cancel:" + goalId + ":" + requestId);
            setStatus(goalId, "CANCELLED");
        }

        @Override
        public Order startTopUp(UUID memberId, UUID goalId, long amountCents, SavingsWallet funding, String idempotencyKey) {
            calls.add("topup:" + goalId + ":" + amountCents + ":" + funding + ":" + idempotencyKey);
            return new Order("ORDER-1", "CREATED", Optional.of("https://paypal.test/checkout/ORDER-1"));
        }

        @Override
        public OrderCapture captureTopUp(UUID goalId, String orderId) {
            calls.add("capture:" + goalId + ":" + orderId);
            return new OrderCapture(orderId, "COMPLETED", 2_500);
        }

        @Override
        public Payout sendPayout(String referenceId, long amountCents, PayoutRecipients.Recipient recipient, SavingsWallet wallet,
                                 String note) {
            calls.add("payout:" + referenceId + ":" + amountCents + ":" + wallet);
            if (failNextPayout != null) {
                RuntimeException e = failNextPayout;
                failNextPayout = null;
                throw e;
            }
            return new Payout(nextPayout, nextReason, nextPayout == PayoutState.SUCCEEDED ? "TXN-" + referenceId : null);
        }
    }
}
