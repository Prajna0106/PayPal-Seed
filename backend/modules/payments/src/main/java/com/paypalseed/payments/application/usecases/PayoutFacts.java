package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import com.paypalseed.payments.api.events.PaymentEvents;
import com.paypalseed.payments.application.ports.PayPalGateway.PayoutItem;
import com.paypalseed.payments.domain.model.PayoutStateMachine;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository.Payout;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns what PayPal says about a payout into exactly one fact each. The API response, an item webhook
 * and a batch webhook can all report the same outcome; only the report that moves the payout forward
 * records a transition and publishes an event. Runs inside the caller's transaction, with the payout
 * row locked.
 */
@Component
public class PayoutFacts {

    /** What one report says. {@code status} is empty when it only carries ids. */
    public record Report(Optional<PayoutStatus> status, String batchId, String itemId, String transactionId, long feeCents,
                         String reason, String source, String paypalEventId) {

        /** A read of the batch or item at PayPal, from the API response or a poll. */
        public static Report of(PayoutItem item, String source, String paypalEventId) {
            Optional<PayoutStatus> status = item.transactionStatus().flatMap(PayoutStateMachine::fromPaypalItem)
                    .or(() -> PayoutStateMachine.fromPaypalBatch(item.batchStatus()));
            String reason = item.errorName().orElse("DENIED".equals(item.batchStatus()) ? "BATCH_DENIED" : null);
            return new Report(status, blankToNull(item.batchId()), item.itemId().orElse(null), item.transactionId().orElse(null),
                    item.feeCents(), reason, source, paypalEventId);
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    private final PayoutRepository payouts;
    private final InboxRepository inbox;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;

    public PayoutFacts(PayoutRepository payouts, InboxRepository inbox, ApplicationEventPublisher events, BusinessClock clock) {
        this.payouts = payouts;
        this.inbox = inbox;
        this.events = events;
        this.clock = clock;
    }

    /** Applies the report and returns the payout as stored afterwards. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payout apply(Payout payout, Report report) {
        Optional<PayoutStatus> next = report.status().flatMap(s -> PayoutStateMachine.apply(payout.status(), s));
        PayoutStatus target = next.orElse(payout.status());
        String reason = next.map(s -> reasonFor(payout.status(), s, report.reason())).orElse(null);
        payouts.update(payout.id(), target, report.batchId(), report.itemId(), report.transactionId(), report.feeCents(),
                isFailure(target) ? reason : null);
        if (next.isPresent()) {
            payouts.addTransition(payout.id(), payout.status(), target, reason, report.source(), report.paypalEventId());
            publish(payout, target, reason, report);
        }
        if (report.batchId() != null) {
            inbox.wake("payout:" + report.batchId()); // webhooks that arrived knowing only PayPal's batch id
        }
        if (report.itemId() != null) {
            inbox.wake("payout:" + report.itemId());
        }
        return new Payout(payout.id(), payout.referenceId(), payout.senderBatchId(), payout.amountCents(),
                payout.recipientType(), payout.receiver(), payout.wallet(), payout.note(), target,
                payout.paypalBatchId() != null ? payout.paypalBatchId() : report.batchId(),
                payout.paypalItemId() != null ? payout.paypalItemId() : report.itemId(),
                report.transactionId() != null ? report.transactionId() : payout.paypalTransactionId(),
                report.feeCents() > 0 ? report.feeCents() : payout.feeCents(),
                isFailure(target) && reason != null ? reason : payout.failureReason());
    }

    private void publish(Payout payout, PayoutStatus target, String reason, Report report) {
        Instant now = clock.now();
        String causedBy = report.paypalEventId() == null ? "payout-api:" + payout.referenceId()
                : "paypal-event:" + report.paypalEventId();
        switch (target) {
            case SUCCEEDED -> events.publishEvent(new PaymentEvents.PayoutSucceeded(Ids.newId(), payout.id(),
                    payout.referenceId(), payout.amountCents(), payout.wallet(),
                    report.transactionId() != null ? report.transactionId() : payout.paypalTransactionId(),
                    report.feeCents() > 0 ? report.feeCents() : payout.feeCents(), now, causedBy));
            case FAILED, BLOCKED -> events.publishEvent(new PaymentEvents.PayoutFailed(Ids.newId(), payout.id(),
                    payout.referenceId(), payout.amountCents(), reason, true, now, causedBy));
            case UNCLAIMED -> events.publishEvent(new PaymentEvents.PayoutFailed(Ids.newId(), payout.id(),
                    payout.referenceId(), payout.amountCents(), reason, false, now, causedBy));
            case RETURNED -> events.publishEvent(new PaymentEvents.PayoutReturned(Ids.newId(), payout.id(),
                    payout.referenceId(), payout.amountCents(), reason, now, causedBy));
            case REQUESTED, PENDING -> {
                // progress only: nothing downstream needs to hear it
            }
        }
    }

    private static boolean isFailure(PayoutStatus s) {
        return s == PayoutStatus.FAILED || s == PayoutStatus.BLOCKED || s == PayoutStatus.UNCLAIMED || s == PayoutStatus.RETURNED;
    }

    private static String reasonFor(PayoutStatus from, PayoutStatus to, String reported) {
        if (reported != null && !reported.isBlank()) {
            return reported;
        }
        return switch (to) {
            case RETURNED -> from == PayoutStatus.UNCLAIMED ? "UNCLAIMED_EXPIRED" : "RETURNED";
            case FAILED, BLOCKED, UNCLAIMED -> to.name();
            default -> null;
        };
    }
}
