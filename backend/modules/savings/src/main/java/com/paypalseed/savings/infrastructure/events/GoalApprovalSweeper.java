package com.paypalseed.savings.infrastructure.events;

import com.paypalseed.savings.application.usecases.ExpireApprovalsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Closes goals whose subscription the member never approved. Worker role; off in tests, which call the use case. */
@Component
@ConditionalOnProperty(name = "seed.jobs.enabled", havingValue = "true", matchIfMissing = true)
class GoalApprovalSweeper {

    private static final Logger log = LoggerFactory.getLogger(GoalApprovalSweeper.class);

    private final ExpireApprovalsUseCase expiry;

    GoalApprovalSweeper(ExpireApprovalsUseCase expiry) {
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "${seed.jobs.savings-approval-sweep-ms:60000}")
    void sweep() {
        int expired = expiry.expireDue();
        if (expired > 0) {
            log.info("Expired {} savings goals that were never approved", expired);
        }
    }
}
