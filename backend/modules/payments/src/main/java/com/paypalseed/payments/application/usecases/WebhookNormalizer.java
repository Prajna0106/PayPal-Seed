package com.paypalseed.payments.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.api.events.PaymentEvents;
import com.paypalseed.payments.api.events.PaymentEvents.ReversalKind;
import com.paypalseed.payments.api.events.PaymentEvents.Source;
import com.paypalseed.payments.application.ports.PayPalGateway.Subscription;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository.InboxEvent;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository.Mandate;
import com.paypalseed.payments.infrastructure.persistence.MoneyRegistry;
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

    private final ObjectMapper json;
    private final MandateRepository mandates;
    private final MoneyRegistry registry;
    private final MoneyFacts facts;
    private final ApplicationEventPublisher events;

    public WebhookNormalizer(ObjectMapper json, MandateRepository mandates, MoneyRegistry registry, MoneyFacts facts,
                             ApplicationEventPublisher events) {
        this.json = json;
        this.mandates = mandates;
        this.registry = registry;
        this.facts = facts;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result handle(InboxEvent event) {
        JsonNode resource = parse(event.payload()).path("resource");
        String causedBy = "paypal-event:" + event.paypalEventId();
        return switch (event.eventType()) {
            case "BILLING.SUBSCRIPTION.CREATED", "BILLING.SUBSCRIPTION.ACTIVATED", "BILLING.SUBSCRIPTION.RE-ACTIVATED",
                 "BILLING.SUBSCRIPTION.SUSPENDED", "BILLING.SUBSCRIPTION.UPDATED", "BILLING.SUBSCRIPTION.CANCELLED",
                 "BILLING.SUBSCRIPTION.EXPIRED" -> subscriptionChanged(resource);
            case "BILLING.SUBSCRIPTION.PAYMENT.FAILED" -> withMandate(resource.path("id").asText(), m -> {
                events.publishEvent(new PaymentEvents.PaymentFailed(Ids.newId(), m.loanId(), Source.AUTOPAY, null,
                        "PAYMENT_FAILED", Instant.now(), causedBy));
                return new Handled();
            });
            case "PAYMENT.SALE.COMPLETED" -> withMandate(resource.path("billing_agreement_id").asText(), m -> {
                facts.captured(m.loanId(), resource.path("id").asText(), "SALE", cents(resource.path("amount").path("total")),
                        cents(resource.path("transaction_fee").path("value")), time(resource), Source.AUTOPAY, causedBy);
                return new Handled();
            });
            case "PAYMENT.SALE.PENDING" -> withMandate(resource.path("billing_agreement_id").asText(), m -> {
                events.publishEvent(new PaymentEvents.PaymentPending(Ids.newId(), m.loanId(), resource.path("id").asText(),
                        cents(resource.path("amount").path("total")), time(resource)));
                return new Handled();
            });
            case "PAYMENT.SALE.REFUNDED", "PAYMENT.SALE.REVERSED" -> reversed(resource.path("id").asText(),
                    resource.path("sale_id").asText(), cents(resource.path("amount").path("total")),
                    event.eventType().endsWith("REFUNDED") ? ReversalKind.REFUND : ReversalKind.REVERSAL, time(resource), causedBy);
            case "PAYMENT.CAPTURE.COMPLETED" -> withLoan(resource, (loanId, purpose) -> {
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
            default -> new Ignored("event type " + event.eventType() + " is not used for repayments");
        };
    }

    private Result subscriptionChanged(JsonNode r) {
        String id = r.path("id").asText();
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

    private Result reversed(String txnId, String parentTxnId, long amountCents, ReversalKind kind, Instant at, String causedBy) {
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
        return mandates.bySubscription(subscriptionId).map(handler::apply)
                .orElseGet(() -> new Parked("subscription:" + subscriptionId));
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
