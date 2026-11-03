package com.paypalseed.lending.infrastructure.web;

import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.SyncDisbursementPayoutUseCase;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The desk's "Check again" on a payout that PayPal has not reported yet (ADR 0029). */
@RestController
@RequestMapping("/api/v1")
class DeskPayoutSyncController {

    private final SyncDisbursementPayoutUseCase sync;

    DeskPayoutSyncController(SyncDisbursementPayoutUseCase sync) {
        this.sync = sync;
    }

    /**
     * A safe re-read, so no {@code Idempotency-Key}: at most one read of PayPal per payout every
     * {@code seed.payments.payout-sync-min-interval}; a call inside that time returns the application as it stands.
     */
    @PostMapping("/desk/applications/{applicationId}/payout/sync")
    ResponseEntity<LoanApplicationView> syncPayout(Actor actor, @PathVariable("applicationId") UUID applicationId) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return ResponseEntity.ok(sync.execute(applicationId));
    }
}
