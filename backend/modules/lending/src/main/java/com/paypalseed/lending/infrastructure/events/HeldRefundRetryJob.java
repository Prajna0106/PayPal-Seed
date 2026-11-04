package com.paypalseed.lending.infrastructure.events;

import com.paypalseed.lending.application.usecases.HeldRefunds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries the refunds PayPal could not make yet for members whose loan never stood (ADR 0024, closing pass). Worker
 * role; off in tests, which call {@link HeldRefunds#retryDue} themselves.
 */
@Component
@ConditionalOnProperty(name = "seed.jobs.enabled", havingValue = "true", matchIfMissing = true)
class HeldRefundRetryJob {

    private static final Logger log = LoggerFactory.getLogger(HeldRefundRetryJob.class);

    private final HeldRefunds refunds;

    HeldRefundRetryJob(HeldRefunds refunds) {
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "${seed.jobs.held-refund-retry-ms:60000}")
    void retry() {
        int refunded = refunds.retryDue();
        if (refunded > 0) {
            log.info("Refunded {} amounts that PayPal could not return earlier", refunded);
        }
    }
}
