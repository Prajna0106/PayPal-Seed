package com.paypalseed.savings.infrastructure.events;

import com.paypalseed.savings.application.usecases.ReconcileGoalsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reads the PayPal subscription of goals that may have missed a webhook (ADR 0028). Worker role; off in tests, which call the use case. */
@Component
@ConditionalOnProperty(name = "seed.jobs.enabled", havingValue = "true", matchIfMissing = true)
class GoalReconciler {

    private static final Logger log = LoggerFactory.getLogger(GoalReconciler.class);

    private final ReconcileGoalsUseCase reconcile;
    private final int batch;

    GoalReconciler(ReconcileGoalsUseCase reconcile, @Value("${seed.savings.reconcile-batch:50}") int batch) {
        this.reconcile = reconcile;
        this.batch = batch;
    }

    @Scheduled(initialDelay = 30_000, fixedDelayString = "${seed.jobs.savings-reconcile-ms:900000}")
    void run() {
        try {
            ReconcileGoalsUseCase.Result result = reconcile.run(batch);
            if (result.examined() > 0) {
                log.info("Savings reconcile: {} goals picked, {} read, {} failed", result.examined(), result.synced(), result.failed());
            }
        } catch (RuntimeException e) {
            log.warn("Savings reconcile failed: {}", e.toString());
        }
    }
}
