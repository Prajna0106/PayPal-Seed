package com.paypalseed.payments.infrastructure.events;

import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutSyncSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reads payouts that may have missed a webhook from PayPal (ADR 0029). Worker role; off in tests, which call the facade. */
@Component
@ConditionalOnProperty(name = "seed.jobs.enabled", havingValue = "true", matchIfMissing = true)
class PayoutReconciler {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconciler.class);

    private final PaymentsFacade payments;
    private final int batch;

    PayoutReconciler(PaymentsFacade payments, @Value("${seed.payments.payout-reconcile-batch:50}") int batch) {
        this.payments = payments;
        this.batch = batch;
    }

    @Scheduled(initialDelay = 30_000, fixedDelayString = "${seed.jobs.payout-reconcile-ms:60000}")
    void run() {
        try {
            PayoutSyncSummary result = payments.syncOpenPayouts(batch);
            if (result.examined() > 0) {
                log.info("Payout reconcile: {} examined, {} read from PayPal, {} failed", result.examined(), result.synced(),
                        result.failed());
            }
        } catch (RuntimeException e) {
            log.warn("Payout reconcile failed: {}", e.toString());
        }
    }
}
