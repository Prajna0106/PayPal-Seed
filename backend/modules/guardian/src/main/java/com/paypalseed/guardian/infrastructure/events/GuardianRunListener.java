package com.paypalseed.guardian.infrastructure.events;

import com.paypalseed.guardian.application.usecases.GuardianRunner;
import com.paypalseed.hardship.api.events.HardshipEvents.GuardianRunRequested;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the Guardian after the case that asked for it has committed. Deliberately not transactional: a run
 * waits on model calls for up to 45 seconds and must not hold a database connection meanwhile; each step
 * it takes commits on its own. The event publication registry still retries an unfinished delivery.
 */
@Component
class GuardianRunListener {

    private final GuardianRunner runner;

    GuardianRunListener(GuardianRunner runner) {
        this.runner = runner;
    }

    @Async
    @TransactionalEventListener
    void on(GuardianRunRequested event) {
        runner.run(event.caseId(), event.loanId(), event.mode());
    }
}
