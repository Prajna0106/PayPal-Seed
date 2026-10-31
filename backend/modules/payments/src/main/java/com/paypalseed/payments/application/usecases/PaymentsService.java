package com.paypalseed.payments.application.usecases;

import com.paypalseed.engines.schedule.MandateReconciliation;
import com.paypalseed.engines.schedule.MandateReconciliation.Activate;
import com.paypalseed.engines.schedule.MandateReconciliation.Cancel;
import com.paypalseed.engines.schedule.MandateReconciliation.CollectionSpec;
import com.paypalseed.engines.schedule.MandateReconciliation.Command;
import com.paypalseed.engines.schedule.MandateReconciliation.Desired;
import com.paypalseed.engines.schedule.MandateReconciliation.MandateStatus;
import com.paypalseed.engines.schedule.MandateReconciliation.Observed;
import com.paypalseed.engines.schedule.MandateReconciliation.Revise;
import com.paypalseed.engines.schedule.MandateReconciliation.Suspend;
import com.paypalseed.engines.schedule.RepaymentPolicy;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.CapturedOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CheckoutOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CreateOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.MandateView;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundOverpayment;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundResult;
import com.paypalseed.payments.api.dto.PaymentsDtos.StartMandate;
import com.paypalseed.payments.api.events.PaymentEvents;
import com.paypalseed.payments.api.events.PaymentEvents.Source;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.Capture;
import com.paypalseed.payments.application.ports.PayPalGateway.Order;
import com.paypalseed.payments.application.ports.PayPalGateway.Refund;
import com.paypalseed.payments.application.ports.PayPalGateway.Revision;
import com.paypalseed.payments.application.ports.PayPalGateway.Subscription;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository.Mandate;
import com.paypalseed.payments.infrastructure.persistence.MoneyRegistry;
import com.paypalseed.payments.infrastructure.persistence.MoneyRegistry.Captured;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link PaymentsFacade}: the only code that asks PayPal to move or arrange money. */
@Service
class PaymentsService implements PaymentsFacade {

    private static final Logger log = LoggerFactory.getLogger(PaymentsService.class);
    private static final LocalTime CHARGE_TIME = LocalTime.of(10, 0);

    private final PayPalGateway paypal;
    private final PayPalCommandExecutor executor;
    private final MandateRepository mandates;
    private final MoneyRegistry registry;
    private final MoneyFacts facts;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    PaymentsService(PayPalGateway paypal, PayPalCommandExecutor executor, MandateRepository mandates, MoneyRegistry registry,
                    MoneyFacts facts, BusinessClock clock, ApplicationEventPublisher events) {
        this.paypal = paypal;
        this.executor = executor;
        this.mandates = mandates;
        this.registry = registry;
        this.facts = facts;
        this.clock = clock;
        this.events = events;
    }

    @Override
    @Transactional
    public MandateView startMandate(StartMandate c) {
        Optional<Mandate> live = mandates.liveForUpdate(c.loanId());
        if (live.isPresent()) {
            return view(live.get()); // idempotent while pending; an active mandate is returned as is
        }
        Instant start = c.firstChargeOn().atTime(CHARGE_TIME).atZone(BusinessClock.PROGRAM_ZONE).toInstant();
        PayPalGateway.CreateSubscription request = new PayPalGateway.CreateSubscription(c.quantity(), "loan:" + c.loanId(),
                start, c.returnUrl(), c.cancelUrl());
        String key = "subscription:" + c.loanId() + ":" + mandates.latest(c.loanId()).map(Mandate::id).map(UUID::toString).orElse("first");
        Subscription s = executor.run("CREATE_SUBSCRIPTION", c.loanId(), key, request, Subscription.class,
                rid -> paypal.createSubscription(request, rid));
        Mandate mandate = new Mandate(Ids.newId(), c.loanId(), c.memberId(), s.id(), MandateStatus.APPROVAL_PENDING,
                c.quantity(), c.firstChargeOn(), s.approveUrl().orElse(null));
        try {
            mandates.insert(mandate);
        } catch (DuplicateKeyException concurrentStart) {
            return view(mandates.live(c.loanId()).orElseThrow());
        }
        return view(mandate);
    }

    @Override
    @Transactional
    public MandateView confirmMandate(UUID loanId, String subscriptionId) {
        Mandate m = mandates.liveForUpdate(loanId)
                .filter(x -> subscriptionId.equals(x.subscriptionId()))
                .orElseThrow(() -> new DomainException(PaymentsErrors.MANDATE_NOT_FOUND, "No pending autopay with that subscription"));
        return view(facts.observed(m, paypal.getSubscription(subscriptionId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MandateView> mandateOf(UUID loanId) {
        return mandates.latest(loanId).map(PaymentsService::view);
    }

    @Override
    @Transactional
    public void cancelMandate(UUID loanId, String reason) {
        Optional<Mandate> live = mandates.liveForUpdate(loanId);
        if (live.isEmpty()) {
            return;
        }
        Mandate m = live.get();
        if (m.status() == MandateStatus.APPROVAL_PENDING) {
            mandates.observe(m.id(), MandateStatus.CANCELLED, m.quantity(), m.nextBillingDate()); // never approved
            return;
        }
        executor.run("CANCEL_SUBSCRIPTION", loanId, "cancel:" + m.id(), Map.of("reason", reason), Boolean.class, rid -> {
            paypal.cancel(m.subscriptionId(), reason, rid);
            return true;
        });
        facts.observed(m, paypal.getSubscription(m.subscriptionId()));
    }

    /** ADR 0005: observe, compute the desired state, issue only the commands that close the gap. */
    @Override
    @Transactional
    public void reconcileMandate(UUID loanId, CollectionSpec spec) {
        Optional<Mandate> live = mandates.liveForUpdate(loanId);
        if (live.isEmpty() || live.get().subscriptionId() == null) {
            return;
        }
        Subscription current;
        try {
            current = paypal.getSubscription(live.get().subscriptionId());
        } catch (PayPalException e) {
            if (e.isTransient()) {
                throw e; // retried with the event
            }
            log.error("Cannot reconcile mandate {} for loan {}: {}", live.get().id(), loanId, e.getMessage());
            return;
        }
        Mandate m = facts.observed(live.get(), current);
        Observed observed = new Observed(m.status(), m.quantity(), Optional.ofNullable(m.nextBillingDate()));
        LocalDate today = clock.today();
        Desired desired = MandateReconciliation.desired(spec, observed, today, RepaymentPolicy.V1);
        List<Command> plan = MandateReconciliation.plan(desired, observed);
        mandates.recordDesired(m.id(), "{\"state\":\"" + desired.state() + "\",\"quantity\":" + desired.quantity()
                + ",\"reason\":\"" + desired.reason() + "\"}");
        String round = m.id() + ":" + desired.state() + ":" + desired.quantity() + ":" + observed.status() + ":"
                + observed.quantity() + ":" + today;
        for (Command command : plan) {
            switch (command) {
                case Revise r -> {
                    Revision rev = executor.run("REVISE_SUBSCRIPTION", loanId, "revise:" + round, Map.of("quantity", r.quantity()),
                            Revision.class, rid -> paypal.reviseQuantity(m.subscriptionId(), r.quantity(), rid));
                    if (rev.approveUrl().isPresent()) {
                        drift(m, "PayPal needs the member to approve a new amount of $" + r.quantity());
                        return;
                    }
                }
                case Suspend s -> executor.run("SUSPEND_SUBSCRIPTION", loanId, "suspend:" + round, Map.of(), Boolean.class, rid -> {
                    paypal.suspend(m.subscriptionId(), "Seed: no instalment due this cycle", rid);
                    return true;
                });
                case Activate a -> executor.run("ACTIVATE_SUBSCRIPTION", loanId, "activate:" + round, Map.of(), Boolean.class, rid -> {
                    paypal.activate(m.subscriptionId(), "Seed: instalment due this cycle", rid);
                    return true;
                });
                case Cancel c -> executor.run("CANCEL_SUBSCRIPTION", loanId, "cancel:" + m.id(), Map.of("reason", "Loan repaid"),
                        Boolean.class, rid -> {
                            paypal.cancel(m.subscriptionId(), "Loan repaid or autopay no longer needed", rid);
                            return true;
                        });
            }
        }
        if (!plan.isEmpty()) {
            facts.observed(m, paypal.getSubscription(m.subscriptionId()));
            log.info("Reconciled mandate {} for loan {}: {}", m.id(), loanId, plan);
        }
        if (desired.drift()) {
            drift(m, "An instalment falls before PayPal's next billing date and will not be collected by autopay");
        }
    }

    private void drift(Mandate m, String detail) {
        events.publishEvent(new PaymentEvents.MandateDriftDetected(Ids.newId(), m.loanId(), m.memberId(), m.id(), detail, Instant.now()));
    }

    @Override
    @Transactional
    public CheckoutOrder createOrder(CreateOrder c) {
        PayPalGateway.CreateOrder request = new PayPalGateway.CreateOrder(c.amountCents(),
                "loan:" + c.loanId() + ":" + c.purpose(), "SEED-PAY-" + Integer.toHexString(c.idempotencyKey().hashCode())
                        + "-" + c.loanId().toString().substring(0, 8), "Seed loan payment");
        Order order = executor.run("CREATE_ORDER", c.loanId(), "order:" + c.loanId() + ":" + c.idempotencyKey(),
                Map.of("amountCents", c.amountCents(), "purpose", c.purpose()), Order.class,
                rid -> paypal.createOrder(request, rid));
        registry.recordOrder(order.id(), c.loanId(), c.purpose().name(), c.amountCents(), order.status());
        return new CheckoutOrder(order.id(), order.status(), order.approveUrl(), c.amountCents());
    }

    @Override
    @Transactional
    public CapturedOrder captureOrder(UUID loanId, String orderId) {
        UUID owner = registry.loanOfOrder(orderId)
                .orElseThrow(() -> new DomainException(PaymentsErrors.ORDER_NOT_FOUND, "No such payment"));
        if (!owner.equals(loanId)) {
            throw new DomainException(PaymentsErrors.ORDER_NOT_FOUND, "No such payment");
        }
        Capture capture = executor.run("CAPTURE_ORDER", loanId, "capture:" + orderId, Map.of("orderId", orderId),
                Capture.class, rid -> paypal.captureOrder(orderId, rid));
        switch (capture.status()) {
            case "COMPLETED" -> facts.captured(loanId, capture.id(), "CAPTURE", capture.amountCents(), capture.feeCents(),
                    capture.createTime(), Source.CHECKOUT, "order:" + orderId);
            case "PENDING" -> events.publishEvent(new PaymentEvents.PaymentPending(Ids.newId(), loanId, capture.id(),
                    capture.amountCents(), capture.createTime()));
            default -> events.publishEvent(new PaymentEvents.PaymentFailed(Ids.newId(), loanId, Source.CHECKOUT, capture.id(),
                    capture.status(), Instant.now(), "order:" + orderId));
        }
        registry.orderStatus(orderId, capture.status());
        return new CapturedOrder(orderId, capture.id(), capture.status(), capture.amountCents());
    }

    @Override
    @Transactional
    public void retryCapture(UUID loanId, UUID attemptId, long amountCents) {
        Mandate m = mandates.liveForUpdate(loanId).filter(x -> x.status().isLive())
                .orElseThrow(() -> new DomainException(PaymentsErrors.MANDATE_NOT_FOUND, "No live autopay for loan " + loanId));
        Capture capture;
        try {
            capture = executor.run("CAPTURE_OUTSTANDING", loanId, "retry:" + attemptId, Map.of("amountCents", amountCents),
                    Capture.class, rid -> paypal.captureOutstanding(m.subscriptionId(), amountCents, "Seed instalment retry", rid));
        } catch (DomainException e) {
            if (e.errorCode() != PaymentsErrors.PAYPAL_REJECTED) {
                throw e; // transient: the listener retries with the same key
            }
            events.publishEvent(new PaymentEvents.PaymentFailed(Ids.newId(), loanId, Source.AUTOPAY_RETRY, null,
                    String.valueOf(e.details().getOrDefault("paypalIssue", "REJECTED")), Instant.now(), "retry:" + attemptId));
            return;
        }
        if ("COMPLETED".equals(capture.status())) {
            facts.captured(loanId, capture.id(), "SALE", amountCents, capture.feeCents(), capture.createTime(),
                    Source.AUTOPAY_RETRY, "retry:" + attemptId);
        } else {
            events.publishEvent(new PaymentEvents.PaymentFailed(Ids.newId(), loanId, Source.AUTOPAY_RETRY, capture.id(),
                    capture.status(), Instant.now(), "retry:" + attemptId));
        }
    }

    /** FR-14: PayPal may know about a charge whose webhook never reached us. */
    @Override
    @Transactional
    public void checkRecentPayments(UUID loanId, LocalDate since) {
        Optional<Mandate> m = mandates.latest(loanId).filter(x -> x.subscriptionId() != null);
        if (m.isEmpty()) {
            return;
        }
        Instant from = since.atStartOfDay(BusinessClock.PROGRAM_ZONE).toInstant();
        List<PayPalGateway.SubscriptionTransaction> found;
        try {
            found = paypal.subscriptionTransactions(m.get().subscriptionId(), from, Instant.now());
        } catch (PayPalException e) {
            if (e.isTransient()) {
                throw e; // retried with the event
            }
            log.warn("Watchdog could not read subscription {} for loan {}: {}", m.get().subscriptionId(), loanId, e.getMessage());
            return;
        }
        for (PayPalGateway.SubscriptionTransaction t : found) {
            if ("COMPLETED".equals(t.status()) && facts.captured(loanId, t.id(), "SALE", t.grossCents(), t.feeCents(), t.time(),
                    Source.RECONCILER, "watchdog")) {
                log.warn("Watchdog found autopay {} for loan {} that no webhook reported", t.id(), loanId);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long refundableCents(UUID loanId) {
        return registry.refundableCents(loanId);
    }

    @Override
    @Transactional
    public RefundResult refundOverpayment(RefundOverpayment c) {
        List<Captured> refundable = registry.refundableForUpdate(c.loanId());
        long available = refundable.stream().mapToLong(Captured::refundableCents).sum();
        if (available < c.amountCents()) {
            throw new DomainException(PaymentsErrors.NOT_ENOUGH_REFUNDABLE, "Only " + available + " cents can be refunded",
                    Map.of("refundableCents", available));
        }
        long remaining = c.amountCents();
        List<String> ids = new ArrayList<>();
        int part = 0;
        for (Captured captured : refundable) {
            if (remaining == 0) {
                break;
            }
            long take = Math.min(remaining, captured.refundableCents());
            String key = "refund:" + c.loanId() + ":" + c.idempotencyKey() + ":" + part++;
            Refund refund = executor.run("REFUND", c.loanId(), key, Map.of("parent", captured.paypalTxnId(), "amountCents", take),
                    Refund.class, rid -> "SALE".equals(captured.kind())
                            ? paypal.refundSale(captured.paypalTxnId(), take, rid)
                            : paypal.refundCapture(captured.paypalTxnId(), take, "SEED-RF-" + Math.abs(key.hashCode()), rid));
            if (registry.recordRefund(refund.id(), c.loanId(), captured.paypalTxnId(), take)) {
                registry.addRefunded(captured.paypalTxnId(), take);
                events.publishEvent(new PaymentEvents.OverpaymentRefundCompleted(Ids.newId(), c.loanId(), refund.id(),
                        captured.paypalTxnId(), take, Instant.now()));
            }
            ids.add(refund.id());
            remaining -= take;
        }
        return new RefundResult(ids, c.amountCents());
    }

    private static MandateView view(Mandate m) {
        return new MandateView(m.id(), m.loanId(), m.status(), Optional.ofNullable(m.subscriptionId()),
                Optional.ofNullable(m.approveUrl()), m.quantity(), Optional.ofNullable(m.nextBillingDate()));
    }
}
