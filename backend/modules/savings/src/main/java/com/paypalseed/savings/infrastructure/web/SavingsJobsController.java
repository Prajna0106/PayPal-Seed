package com.paypalseed.savings.infrastructure.web;

import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import com.paypalseed.savings.application.usecases.ReconcileGoalsUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal job (service token): the savings reconcile, the same batch the worker runs on a timer (ADR 0028). */
@RestController
class SavingsJobsController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final ReconcileGoalsUseCase reconcile;

    SavingsJobsController(ReconcileGoalsUseCase reconcile) {
        this.reconcile = reconcile;
    }

    @PostMapping("/internal/jobs/savings/reconcile")
    ReconcileGoalsUseCase.Result reconcile(Actor actor, @RequestParam(value = "limit", required = false) Integer limit) {
        actor.requireAnyRole(Role.SERVICE);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return reconcile.run(size);
    }
}
