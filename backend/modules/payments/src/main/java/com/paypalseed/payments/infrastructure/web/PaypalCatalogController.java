package com.paypalseed.payments.infrastructure.web;

import com.paypalseed.payments.application.usecases.PaypalCatalogService;
import com.paypalseed.payments.application.usecases.PaypalCatalogService.Ensured;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-only bootstrap of the PayPal billing catalog (ADR 0013). Idempotent: it finds the Seed product
 * and plans and creates only what is missing, so running it twice changes nothing.
 */
@RestController
class PaypalCatalogController {

    record BootstrapResult(String productId, String savingsPlanId, String loanPlanId, boolean createdProduct,
                           boolean createdSavingsPlan, boolean createdLoanPlan) {
    }

    private final PaypalCatalogService catalog;

    PaypalCatalogController(PaypalCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/internal/paypal/catalog/bootstrap")
    BootstrapResult bootstrap(Actor actor) {
        actor.requireAnyRole(Role.SERVICE);
        Ensured e = catalog.ensure();
        return new BootstrapResult(e.catalog().productId(), e.catalog().savingsPlanId(), e.catalog().loanPlanId(),
                e.createdProduct(), e.createdSavingsPlan(), e.createdLoanPlan());
    }
}
