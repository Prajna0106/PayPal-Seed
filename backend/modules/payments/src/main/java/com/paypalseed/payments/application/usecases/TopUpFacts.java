package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.events.PaymentEvents;
import com.paypalseed.payments.infrastructure.persistence.TopUpRepository;
import com.paypalseed.payments.infrastructure.persistence.TopUpRepository.TopUp;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A top-up capture can be reported by the capture call and by the {@code PAYMENT.CAPTURE.COMPLETED}
 * webhook. The first to attach the capture id to the order publishes the event; the other does nothing.
 */
@Component
public class TopUpFacts {

    private final TopUpRepository topUps;
    private final ApplicationEventPublisher events;

    public TopUpFacts(TopUpRepository topUps, ApplicationEventPublisher events) {
        this.topUps = topUps;
        this.events = events;
    }

    /** Returns true when this call announced the capture. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean captured(TopUp topUp, String captureId, Instant occurredAt, String causedBy) {
        if (!topUps.claimCapture(topUp.orderId(), captureId)) {
            return false;
        }
        events.publishEvent(new PaymentEvents.SavingsTopUpCaptured(Ids.newId(), topUp.memberId(), topUp.goalId(), captureId,
                topUp.amountCents(), topUp.funding(), occurredAt, causedBy));
        return true;
    }
}
