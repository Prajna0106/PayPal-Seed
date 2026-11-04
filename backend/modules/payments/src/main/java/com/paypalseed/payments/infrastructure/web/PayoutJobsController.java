package com.paypalseed.payments.infrastructure.web;

import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutSyncSummary;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal job (service token): the payout reconcile, the same batch the worker runs on a timer (ADR 0029). */
@RestController
class PayoutJobsController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final PaymentsFacade payments;

    PayoutJobsController(PaymentsFacade payments) {
        this.payments = payments;
    }

    @PostMapping("/internal/jobs/payouts/reconcile")
    PayoutSyncSummary reconcile(Actor actor, @RequestParam(value = "limit", required = false) Integer limit) {
        actor.requireAnyRole(Role.SERVICE);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return payments.syncOpenPayouts(size);
    }
}
