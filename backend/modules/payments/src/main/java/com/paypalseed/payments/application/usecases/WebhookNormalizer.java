package com.paypalseed.payments.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import com.paypalseed.payments.api.events.PaymentEvents;
import com.paypalseed.payments.api.events.PaymentEvents.ReversalKind;
import com.paypalseed.payments.api.events.PaymentEvents.SavingsSubscriptionState;
import com.paypalseed.payments.api.events.PaymentEvents.Source;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.PayoutItem;
import com.paypalseed.payments.application.ports.PayPalGateway.Subscription;
import com.paypalseed.payments.application.usecases.PayoutFacts.Report;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository.InboxEvent;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository.Mandate;
import com.paypalseed.payments.infrastructure.persistence.MoneyRegistry;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository.Payout;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository;
import com.paypalseed.payments.infrastructure.persistence.SavingsSubscriptionRepository.SavingsSubscription;
import com.paypalseed.payments.infrastructure.persistence.TopUpRepository;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.kernel.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps one verified PayPal webhook to the normalised payment facts (04-paypal-integration §7).
 * Events whose parent is not known yet (a sale before its subscription, a refund before its
 * capture) are parked and replayed when the parent is registered.
 */
@Component
public class WebhookNormalizer {

    public sealed interface Result permits Handled, Ignored, Parked {
    }

    public record Handled() implements Result {
    }

    public record Ignored(String why) implements Result {
    }

    public record Parked(String waitFor) implements Result {
    }

    private static final Pattern LOAN_CUSTOM_ID = Pattern.compile("^loan:([0-9a-f-]{36})(?::([A-Z_]+))?$");
    private static final Pattern PARENT_CAPTURE = Pattern.compile("/captures/([^/]+)$");
    /** A payout report with no sender id that no Seed payout claims after this many tries belongs to someone else. */
    private static final int UNKNOWN_PAYOUT_ATTEMPTS = 6;

    private final ObjectMapper json;
    private final MandateRepository mandates;
    private final MoneyRegistry registry;
    private final MoneyFacts facts;
    private final ApplicationEventPublisher events;
    private final PayPalGateway paypal;
    private final PayoutRepository payouts;
    private final PayoutFacts payoutFacts;
    private final SavingsSubscriptionRepository savingsSubscriptions;
    private final TopUpRepository topUps;
    private final TopUpFacts topUpFacts;

    public WebhookNormalizer(ObjectMapper json, MandateRepository mandates, MoneyRegistry registry, MoneyFacts facts,
                             ApplicationEventPublisher events, PayPalGateway paypal, PayoutRepository payouts,
                             PayoutFacts payoutFacts, SavingsSubscriptionRepository savingsSubscriptions,
                             TopUpRepository topUps, TopUpFacts topUpFacts) {
        this.json = json;
        this.mandates = mandates;
        this.registry = registry;
        this.facts = facts;
        this.events = events;
        this.paypal = paypal;
        this.payouts = payouts;
        this.payoutFacts = payoutFacts;
        this.savingsSubscriptions = savingsSubscriptions;
        this.topUps = topUps;
        this.topUpFacts = topUpFacts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result handle(InboxEvent event) {
        JsonNode root = parse(event.payload());
        JsonNode resource = root.path("resource");
        String causedBy = "paypal-event:" + event.paypalEventId();
        return switch (event.eventType()) {
            case "BILLING.SUBSCRIPTION.CREATED", "BILLING.SUBSCRIPTION.ACTIVATED", "BILLING.SUBSCRIPTION.RE-ACTIVATED",
                 "BILLING.SUBSCRIPTION.SUSPENDED", "BILLING.SUBSCRIPTION.UPDATED", "BILLING.SUBSCRIPTION.CANCELLED",
                 "BILLING.SUBSCRIPTION.EXPIRED" -> subscriptionChanged(event.eventType(), resource, root.path("create_time").asText(""), causedBy);
            case "BILLING.SUBSCRIPTION.PAYMENT.FAILED" -> withMandate(resource.path("id").asText(), m -> {
                events.publishEvent(new PaymentEvents.PaymentFailed(Ids.newId(), m.loanId(), Source.AUTOPAY, null,
                        "PAYMENT_FAILED", Instant.now(), causedBy));
                return new Handled();
            });
            case "PAYMENT.SALE.COMPLETED" -> saleCompleted(resource, causedBy);
            case "PAYMENT.SALE.PENDING" -> withMandate(resource.path("billing_agreement_id").asText(), m -> {
                events.publishEvent(new PaymentEvents.PaymentPending(Ids.newId(), m.loanId(), resource.path("id").asText(),
                        cents(resource.path("amount").path("total")), time(resource)));
                return new Handled();
            });
            case "PAYMENT.SALE.REFUNDED", "PAYMENT.SALE.REVERSED" -> reversed(resource.path("id").asText(),
                    resource.path("sale_id").asText(), cents(resource.path("amount").path("total")),
                    event.eventType().endsWith("REFUNDED") ? ReversalKind.REFUND : ReversalKind.REVERSAL, time(resource), causedBy);
            case "PAYMENT.CAPTURE.COMPLETED" -> isTopUp(resource) ? topUpCaptured(resource, causedBy)
                    : isDonation(resource) ? donationCaptured(resource)
                    : withLoan(resource, (loanId, purpose) -> {
                        facts.captured(loanId, resource.path("id").asText(), "CAPTURE", cents(resource.path("amount").path("value")),
                                cents(resource.path("seller_receivable_breakdown").path("paypal_fee").path("value")), time(resource),
                                "LINK".equals(purpose) ? Source.PAYMENT_LINK : Source.CHECKOUT, causedBy);
                        return new Handled();
                    });
            case "PAYMENT.CAPTURE.PENDING" -> withLoan(resource, (loanId, purpose) -> {
                events.publishEvent(new PaymentEvents.PaymentPending(Ids.newId(), loanId, resource.path("id").asText(),
                        cents(resource.path("amount").path("value")), time(resource)));
                return new Handled();
            });
            case "PAYMENT.CAPTURE.DECLINED", "PAYMENT.CAPTURE.DENIED" -> withLoan(resource, (loanId, purpose) -> {
                events.publishEvent(new PaymentEvents.PaymentFailed(Ids.newId(), loanId, Source.CHECKOUT,
                        resource.path("id").asText(), event.eventType(), Instant.now(), causedBy));
                return new Handled();
            });
            case "PAYMENT.CAPTURE.REFUNDED", "PAYMENT.CAPTURE.REVERSED" -> reversed(resource.path("id").asText(),
                    parentCapture(resource), cents(resource.path("amount").path("value")),
                    event.eventType().endsWith("REFUNDED") ? ReversalKind.REFUND : ReversalKind.REVERSAL, time(resource), causedBy);
            case "PAYMENT.PAYOUTS-ITEM.SUCCEEDED" -> payoutItem(event, resource, PayoutStatus.SUCCEEDED);
            case "PAYMENT.PAYOUTS-ITEM.FAILED", "PAYMENT.PAYOUTS-ITEM.DENIED", "PAYMENT.PAYOUTS-ITEM.CANCELED" ->
                    payoutItem(event, resource, PayoutStatus.FAILED);
            case "PAYMENT.PAYOUTS-ITEM.BLOCKED" -> payoutItem(event, resource, PayoutStatus.BLOCKED);
            case "PAYMENT.PAYOUTS-ITEM.RETURNED" -> payoutItem(event, resource, PayoutStatus.RETURNED);
            case "PAYMENT.PAYOUTS-ITEM.UNCLAIMED" -> payoutItem(event, resource, PayoutStatus.UNCLAIMED);
            case "PAYMENT.PAYOUTSBATCH.DENIED" -> payoutBatchDenied(event, resource);
            case "PAYMENT.PAYOUTSBATCH.SUCCESS" -> payoutBatchProcessed(event, resource);
            default -> new Ignored("event type " + event.eventType() + " is not used by payments");
        };
    }

    /** A sale on a savings subscription is a deposit for its goal; any other sale belongs to a loan mandate. */
    private Result saleCompleted(JsonNode resource, String causedBy) {
        String subscriptionId = resource.path("billing_agreement_id").asText();
        Optional<SavingsSubscription> saving = savingsSubscriptions.bySubscription(subscriptionId);
        if (saving.isPresent()) {
            SavingsSubscription s = saving.get();
            events.publishEvent(new PaymentEvents.SavingsDepositReceived(Ids.newId(), s.goalId(), s.memberId(), subscriptionId,
                    resource.path("id").asText(), cents(resource.path("amount").path("total")),
                    cents(resource.path("transaction_fee").path("value")), time(resource), causedBy));
            return new Handled();
        }
        return withMandate(subscriptionId, m -> {
            facts.captured(m.loanId(), resource.path("id").asText(), "SALE", cents(resource.path("amount").path("total")),
                    cents(resource.path("transaction_fee").path("value")), time(resource), Source.AUTOPAY, causedBy);
            return new Handled();
        });
    }

    private Result subscriptionChanged(String eventType, JsonNode r, String eventTime, String causedBy) {
        String id = r.path("id").asText();
        Optional<SavingsSubscription> saving = savingsSubscriptions.bySubscription(id);
        if (saving.isPresent() || r.path("custom_id").asText("").startsWith("goal:")) {
            if (saving.isEmpty()) {
                return new Parked("subscription:" + id); // PayPal was faster than our commit; the insert wakes it
            }
            savingsSubscriptions.status(id, r.path("status").asText());
            savingsState(eventType).ifPresent(state -> events.publishEvent(new PaymentEvents.SavingsSubscriptionStateChanged(
                    Ids.newId(), saving.get().goalId(), saving.get().memberId(), id, state, subscriptionTime(r, eventTime), causedBy)));
            return new Handled(); // a savings deposit plan, not a loan mandate
        }
        return withMandate(id, m -> {
            Subscription observed = new Subscription(id, r.path("status").asText(m.status().name()),
                    r.path("quantity").asLong(m.quantity()),
                    Optional.ofNullable(r.path("billing_info").path("next_billing_time").asText(null)).map(Instant::parse),
                    Optional.empty(), Optional.empty());
            Mandate locked = mandates.liveForUpdate(m.loanId()).filter(x -> id.equals(x.subscriptionId())).orElse(m);
            facts.observed(locked, observed);
            return new Handled();
        });
    }

    private static Optional<SavingsSubscriptionState> savingsState(String eventType) {
        return switch (eventType) {
            case "BILLING.SUBSCRIPTION.ACTIVATED", "BILLING.SUBSCRIPTION.RE-ACTIVATED" -> Optional.of(SavingsSubscriptionState.ACTIVE);
            case "BILLING.SUBSCRIPTION.SUSPENDED" -> Optional.of(SavingsSubscriptionState.SUSPENDED);
            case "BILLING.SUBSCRIPTION.CANCELLED" -> Optional.of(SavingsSubscriptionState.CANCELLED);
            case "BILLING.SUBSCRIPTION.EXPIRED" -> Optional.of(SavingsSubscriptionState.EXPIRED);
            default -> Optional.empty(); // CREATED and UPDATED change nothing the goal cares about
        };
    }

    private Result reversed(String txnId, String parentTxnId, long amountCents, ReversalKind kind, Instant at, String causedBy) {
        if (topUps.isCapture(parentTxnId)) {
            return new Ignored("refund " + txnId + " is of a savings top-up, not a loan payment");
        }
        if (registry.isOurRefund(txnId)) {
            return new Ignored("refund " + txnId + " was issued by Seed and is already recorded");
        }
        return registry.captured(parentTxnId).<Result>map(parent -> {
            events.publishEvent(new PaymentEvents.PaymentReversed(Ids.newId(), parent.loanId(), txnId, parentTxnId, kind,
                    amountCents, at, causedBy));
            return new Handled();
        }).orElseGet(() -> new Parked("txn:" + parentTxnId));
    }

    private interface MandateHandler {
        Result apply(Mandate m);
    }

    private Result withMandate(String subscriptionId, MandateHandler handler) {
        if (savingsSubscriptions.isSavingsSubscription(subscriptionId)) {
            return new Ignored("subscription " + subscriptionId + " deposits into a savings goal");
        }
        return mandates.bySubscription(subscriptionId).map(handler::apply)
                .orElseGet(() -> new Parked("subscription:" + subscriptionId));
    }

    private static boolean isTopUp(JsonNode resource) {
        return resource.path("custom_id").asText("").startsWith("goal:");
    }

    private static boolean isDonation(JsonNode resource) {
        return resource.path("custom_id").asText("").startsWith("reserve-donation:");
    }

    private Result donationCaptured(JsonNode resource) {
        events.publishEvent(new PaymentEvents.DonationCaptured(Ids.newId(), resource.path("id").asText(),
                resource.path("custom_id").asText(), cents(resource.path("amount").path("value")),
                cents(resource.path("seller_receivable_breakdown").path("paypal_fee").path("value")), time(resource)));
        return new Handled();
    }

    private Result topUpCaptured(JsonNode resource, String causedBy) {
        String orderId = resource.path("supplementary_data").path("related_ids").path("order_id").asText("");
        if (orderId.isBlank()) {
            return new Ignored("top-up capture carries no order id");
        }
        return topUps.byOrder(orderId).<Result>map(topUp -> {
            topUpFacts.captured(topUp, resource.path("id").asText(), time(resource), causedBy);
            return new Handled();
        }).orElseGet(() -> new Parked("topup:" + orderId));
    }

    private Result payoutItem(InboxEvent event, JsonNode resource, PayoutStatus status) {
        String batchId = resource.path("payout_batch_id").asText("");
        String itemId = resource.path("payout_item_id").asText("");
        return withPayout(event, itemId, batchId, senderBatchId(resource), payout -> {
            payoutFacts.apply(payout, new Report(Optional.of(status), emptyToNull(batchId), emptyToNull(itemId),
                    emptyToNull(resource.path("transaction_id").asText("")), cents(resource.path("payout_item_fee").path("value")),
                    emptyToNull(resource.path("errors").path("name").asText("")), "WEBHOOK", event.paypalEventId()));
            return new Handled();
        });
    }

    private Result payoutBatchDenied(InboxEvent event, JsonNode resource) {
        JsonNode header = resource.path("batch_header");
        String batchId = header.path("payout_batch_id").asText("");
        return withPayout(event, "", batchId, header.path("sender_batch_header").path("sender_batch_id").asText(""), payout -> {
            payoutFacts.apply(payout, new Report(Optional.of(PayoutStatus.FAILED), emptyToNull(batchId), null, null, 0,
                    emptyToNull(header.path("errors").path("name").asText("BATCH_DENIED")), "WEBHOOK", event.paypalEventId()));
            return new Handled();
        });
    }

    /** A processed batch does not say how its items ended, so the item is read back from PayPal. */
    private Result payoutBatchProcessed(InboxEvent event, JsonNode resource) {
        JsonNode header = resource.path("batch_header");
        String batchId = header.path("payout_batch_id").asText("");
        return withPayout(event, "", batchId, header.path("sender_batch_header").path("sender_batch_id").asText(""), payout -> {
            PayoutItem observed = paypal.getPayoutBatch(batchId);
            payoutFacts.apply(payout, Report.of(observed, "WEBHOOK", event.paypalEventId()));
            return new Handled();
        });
    }

    private interface PayoutHandler {
        Result apply(Payout payout);
    }

    private Result withPayout(InboxEvent event, String itemId, String batchId, String senderBatchId, PayoutHandler handler) {
        Optional<Payout> found = payouts.findForUpdate(itemId, batchId, senderBatchId);
        if (found.isPresent()) {
            return handler.apply(found.get());
        }
        if (!senderBatchId.isBlank() && !senderBatchId.startsWith(PayoutService.SENDER_PREFIX)) {
            return new Ignored("payout " + senderBatchId + " was not sent by Seed");
        }
        String waitFor = !senderBatchId.isBlank() ? senderBatchId : !batchId.isBlank() ? batchId : itemId;
        if (waitFor.isBlank() || senderBatchId.isBlank() && event.attempts() >= UNKNOWN_PAYOUT_ATTEMPTS) {
            return new Ignored("payout report names no payout Seed knows");
        }
        return new Parked("payout:" + waitFor);
    }

    private static String senderBatchId(JsonNode resource) {
        String batch = resource.path("sender_batch_id").asText("");
        if (!batch.isBlank()) {
            return batch;
        }
        String item = resource.path("payout_item").path("sender_item_id").asText("");
        return item.isBlank() ? "" : PayoutService.SENDER_PREFIX + item;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private interface LoanHandler {
        Result apply(UUID loanId, String purpose);
    }

    private static Result withLoan(JsonNode resource, LoanHandler handler) {
        Matcher m = LOAN_CUSTOM_ID.matcher(resource.path("custom_id").asText(""));
        if (!m.matches()) {
            return new Ignored("capture is not for a Seed loan");
        }
        return handler.apply(UUID.fromString(m.group(1)), m.group(2));
    }

    private static String parentCapture(JsonNode refund) {
        for (JsonNode link : refund.path("links")) {
            if ("up".equals(link.path("rel").asText())) {
                Matcher m = PARENT_CAPTURE.matcher(link.path("href").asText());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return refund.path("capture_id").asText("");
    }

    private static long cents(JsonNode value) {
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? 0 : Money.parse(value.asText()).cents();
    }

    /**
     * When a subscription changed. A subscription resource carries its creation time in {@code create_time} and the
     * time of the change in {@code update_time}; using the creation time would give every state change of one
     * subscription the same instant and reports could not be ordered (ADR 0020). The event's own time is the fallback.
     */
    private static Instant subscriptionTime(JsonNode resource, String eventTime) {
        String t = resource.path("update_time").asText("");
        if (t.isBlank()) {
            t = eventTime;
        }
        return t == null || t.isBlank() ? Instant.now() : Instant.parse(t);
    }

    private static Instant time(JsonNode resource) {
        String t = resource.path("create_time").asText(resource.path("update_time").asText(""));
        return t.isBlank() ? Instant.now() : Instant.parse(t);
    }

    private JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("stored webhook payload is not JSON", e);
        }
    }
}
