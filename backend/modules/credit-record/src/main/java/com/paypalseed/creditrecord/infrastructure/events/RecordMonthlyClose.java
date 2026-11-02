package com.paypalseed.creditrecord.infrastructure.events;

import com.paypalseed.creditrecord.application.usecases.SavingsMonthClose;
import com.paypalseed.platform.kernel.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brings members' saving months up to date a few times a day, so a month with no deposit is counted when it ends
 * and not only when the next deposit arrives. Safe to run on every process: each month is one fact per member.
 */
@Component
@ConditionalOnProperty(name = "seed.jobs.enabled", havingValue = "true", matchIfMissing = true)
class RecordMonthlyClose {

    private static final Logger log = LoggerFactory.getLogger(RecordMonthlyClose.class);
    private static final int BATCH = 200;
    private static final int MAX_BATCHES = 50;

    private final SavingsMonthClose close;
    private final BusinessClock clock;

    RecordMonthlyClose(SavingsMonthClose close, BusinessClock clock) {
        this.close = close;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${seed.jobs.record-close-ms:21600000}", initialDelayString = "${seed.jobs.record-close-initial-ms:60000}")
    void run() {
        try {
            // Bounded, so a member whose close keeps being refused cannot hold the job in a loop.
            for (int batch = 0; batch < MAX_BATCHES && close.closeThrough(clock.today(), BATCH) == BATCH; batch++) {
                log.debug("Saving-month close: batch {} full, continuing", batch);
            }
        } catch (RuntimeException e) {
            log.warn("Saving-month close failed; it will run again", e);
        }
    }
}
