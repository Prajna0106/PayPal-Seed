package com.paypalseed.payments.application.usecases;

import com.paypalseed.engines.schedule.MandateReconciliation.MandateStatus;
import com.paypalseed.payments.api.events.PaymentEvents;
import com.paypalseed.payments.api.events.PaymentEvents.Source;
import com.paypalseed.payments.application.ports.PayPalGateway.Subscription;
import com.paypalseed.payments.infrastructure.persistence.CommandRepository;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository;
import com.paypalseed.payments.infrastructure.persistence.MandateRepository.Mandate;
import com.paypalseed.payments.infrastructure.persistence.MoneyRegistry;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns what PayPal reports into exactly one fact each: a webhook, an API response and the
 * watchdog can all report the same money, and only the first registration publishes an event.
 * Runs inside the caller's transaction, so the registry row and the outbox event commit together.
 */
@Component
public class MoneyFacts {

    private final MoneyRegistry registry;
    private final MandateRepository mandates;
    private final CommandRepository commands;
    private final InboxRepository inbox;
    private final ApplicationEventPublisher events;

    public MoneyFacts(MoneyRegistry registry, MandateRepository mandates, CommandRepository commands, InboxRepository inbox,
                      ApplicationEventPublisher events) {
        this.registry = registry;
        this.mandates = mandates;
        this.commands = commands;
        this.inbox = inbox;
        this.events = events;
    }

    /** Returns true when this is the first time Seed hears of the transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean captured(UUID loanId, String txnId, String kind, long grossCents, long feeCents, Instant occurredAt,
                            Source source, String causedBy) {
        if (!registry.recordCaptured(txnId, loanId, kind, grossCents, occurredAt)) {
            return false;
        }
        events.publishEvent(new PaymentEvents.PaymentCaptured(Ids.newId(), loanId, txnId, source, grossCents, feeCents,
                occurredAt, null, causedBy));
        inbox.wake("txn:" + txnId); // a refund or reversal that arrived first can now be resolved
        return true;
    }

    /**
     * Records the subscription state PayPal reports and publishes lifecycle transitions once (the
     * mandate row is locked by the caller).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Mandate observed(Mandate mandate, Subscription s) {
        MandateStatus status = MandateStatus.valueOf(s.status());
        LocalDate nextBilling = s.nextBillingTime().map(BusinessClock::businessDateOf).orElse(null);
        mandates.observe(mandate.id(), status, s.quantity(), nextBilling);
        Instant now = Instant.now();
        if (mandate.status() == MandateStatus.APPROVAL_PENDING && status.isLive()) {
            events.publishEvent(new PaymentEvents.MandateActivated(Ids.newId(), mandate.loanId(), mandate.id(), s.id(), now));
            inbox.wake("subscription:" + s.id());
        }
        if (mandate.status().isLive() && !status.isLive()) {
            PaymentEvents.MandateEndCause cause = commands.cancelRequested(mandate.loanId()) ? PaymentEvents.MandateEndCause.SEED
                    : status == MandateStatus.EXPIRED ? PaymentEvents.MandateEndCause.PAYPAL : PaymentEvents.MandateEndCause.MEMBER;
            events.publishEvent(new PaymentEvents.MandateEnded(Ids.newId(), mandate.loanId(), mandate.id(), cause, now));
        }
        return new Mandate(mandate.id(), mandate.loanId(), mandate.memberId(), s.id(), status, s.quantity(), nextBilling,
                mandate.approveUrl());
    }
}
