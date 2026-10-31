package com.paypalseed.app;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * At-least-once delivery across modules: a listener that failed (PayPal down, a reversal ahead of
 * its payment) leaves its event publication incomplete; it is re-submitted here until it succeeds.
 * Listeners are idempotent, so a re-submission after a late success is harmless.
 */
@Component
@ConditionalOnProperty(name = "seed.jobs.enabled", havingValue = "true", matchIfMissing = true)
class BackgroundJobs {

    private final IncompleteEventPublications incomplete;

    BackgroundJobs(IncompleteEventPublications incomplete) {
        this.incomplete = incomplete;
    }

    @Scheduled(fixedDelayString = "${seed.jobs.event-retry-ms:60000}")
    void retryIncompleteEvents() {
        incomplete.resubmitIncompletePublicationsOlderThan(Duration.ofSeconds(60));
    }
}
