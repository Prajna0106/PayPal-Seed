package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.application.usecases.WebhookNormalizer.Handled;
import com.paypalseed.payments.application.usecases.WebhookNormalizer.Ignored;
import com.paypalseed.payments.application.usecases.WebhookNormalizer.Parked;
import com.paypalseed.payments.application.usecases.WebhookNormalizer.Result;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository.InboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the webhook inbox: one event per transaction, claimed with {@code SKIP LOCKED} so several
 * worker instances never process the same event. Failures back off exponentially with jitter.
 * Parked events wait until their parent is registered.
 */
@Component
public class InboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);
    private static final int MAX_ATTEMPTS = 12;

    private final InboxRepository inbox;
    private final WebhookNormalizer normalizer;
    private final TransactionTemplate tx;
    private final TransactionTemplate savepoint;

    public InboxProcessor(InboxRepository inbox, WebhookNormalizer normalizer, PlatformTransactionManager txManager) {
        this.inbox = inbox;
        this.normalizer = normalizer;
        this.tx = new TransactionTemplate(txManager);
        this.savepoint = new TransactionTemplate(txManager);
        this.savepoint.setPropagationBehavior(TransactionTemplate.PROPAGATION_NESTED);
    }

    /** Returns how many events were handled, at most {@code max}. */
    public int drain(int max) {
        int handled = 0;
        while (handled < max) {
            Boolean didWork = tx.execute(status -> {
                List<InboxEvent> claimed = inbox.claimDue(1);
                if (claimed.isEmpty()) {
                    return false;
                }
                process(claimed.get(0));
                return true;
            });
            if (!Boolean.TRUE.equals(didWork)) {
                break;
            }
            handled++;
        }
        return handled;
    }

    private void process(InboxEvent event) {
        try {
            // savepoint: a failing event rolls back its own effects but keeps the claim and the failure record
            Result result = savepoint.execute(status -> normalizer.handle(event));
            switch (result) {
                case Handled h -> inbox.done(event.id(), "PROCESSED");
                case Ignored i -> {
                    log.debug("Ignoring webhook {}: {}", event.paypalEventId(), i.why());
                    inbox.done(event.id(), "IGNORED");
                }
                case Parked p -> {
                    if (event.attempts() >= MAX_ATTEMPTS) {
                        log.error("Webhook {} still waiting for {} after {} attempts", event.paypalEventId(), p.waitFor(), event.attempts());
                    }
                    inbox.park(event.id(), p.waitFor(), Instant.now().plus(backoff(event.attempts())));
                }
                case null -> throw new IllegalStateException("normalizer returned nothing");
            }
        } catch (RuntimeException e) {
            boolean giveUp = event.attempts() + 1 >= MAX_ATTEMPTS;
            log.error("Webhook {} ({}) failed, attempt {}{}", event.paypalEventId(), event.eventType(), event.attempts() + 1,
                    giveUp ? "; giving up (dead letter)" : "", e);
            inbox.failed(event.id(), e.getClass().getSimpleName() + ": " + e.getMessage(),
                    Instant.now().plus(backoff(event.attempts())), giveUp);
        }
    }

    /** Full jitter spreads retries so a PayPal outage does not end in a synchronised retry storm. */
    static Duration backoff(int attempts) {
        long capMillis = Math.min(300_000L, 2_000L * (1L << Math.min(attempts, 16)));
        return Duration.ofMillis((long) (Math.random() * capMillis));
    }
}
