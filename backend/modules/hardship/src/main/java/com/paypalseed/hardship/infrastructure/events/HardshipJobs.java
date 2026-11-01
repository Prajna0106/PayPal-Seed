package com.paypalseed.hardship.infrastructure.events;

import com.paypalseed.hardship.application.usecases.CaseTimers;
import com.paypalseed.hardship.application.usecases.HardshipScan;
import com.paypalseed.platform.kernel.BusinessClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker-role fallback for Render Workflows (07-implementation-plan T14): the same idempotent jobs the internal
 * endpoints run, on a local schedule.
 */
@Component
@ConditionalOnProperty(name = "seed.jobs.enabled", havingValue = "true", matchIfMissing = true)
class HardshipJobs {

    private final HardshipScan scan;
    private final CaseTimers timers;
    private final BusinessClock clock;
    private final int shardCount;

    HardshipJobs(HardshipScan scan, CaseTimers timers, BusinessClock clock, @Value("${seed.cob.shard-count:8}") int shardCount) {
        this.scan = scan;
        this.timers = timers;
        this.clock = clock;
        this.shardCount = shardCount;
    }

    @Scheduled(fixedDelayString = "${seed.jobs.hardship-sweep-ms:30000}")
    void sweep() {
        timers.sweep(200);
    }

    @Scheduled(cron = "${seed.jobs.hardship-scan-cron:0 15 6 * * *}", zone = "America/Los_Angeles")
    void dailyScan() {
        scan.scanAll(clock.today(), shardCount, 200);
    }
}
