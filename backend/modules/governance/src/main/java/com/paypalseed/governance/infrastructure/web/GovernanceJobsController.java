package com.paypalseed.governance.infrastructure.web;

import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.ChainVerification;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal job (service token): the nightly audit-chain verification. */
@RestController
class GovernanceJobsController {

    private static final Logger log = LoggerFactory.getLogger(GovernanceJobsController.class);

    private final GovernanceFacade governance;

    GovernanceJobsController(GovernanceFacade governance) {
        this.governance = governance;
    }

    @PostMapping("/internal/jobs/governance/verify-audit-chain")
    ChainVerification verifyAuditChain(Actor actor) {
        actor.requireAnyRole(Role.SERVICE);
        ChainVerification result = governance.verifyChain();
        if (!result.intact()) {
            // Sev-1: someone edited the audit log outside the application.
            log.error("Audit chain broken at seq {} after {} intact entries", result.firstBrokenSeq(), result.entries());
        }
        return result;
    }
}
