package com.paypalseed.deskquery.infrastructure.events;

import com.paypalseed.deskquery.application.usecases.SavingMonthsRebuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the saving-months rebuild once, when the application first starts after the business-zone rule shipped (ADR 0027).
 * Start-up must not depend on it: a failure is logged and the next start tries again, and every role may start it, since the
 * rebuild is idempotent and locks one member at a time.
 */
@Component
class SavingMonthsRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SavingMonthsRebuildRunner.class);

    private final SavingMonthsRebuild rebuild;

    SavingMonthsRebuildRunner(SavingMonthsRebuild rebuild) {
        this.rebuild = rebuild;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            rebuild.runOnce();
        } catch (RuntimeException e) {
            log.warn("Saving months were not rebuilt; they will be tried again at the next start: {}", e.getClass().getSimpleName());
        }
    }
}
