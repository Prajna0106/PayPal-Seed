package com.paypalseed.savings.infrastructure.web;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.ActorAuthentication;
import com.paypalseed.savings.api.dto.SavingsGoalResponse;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.usecases.SyncGoalUseCase;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own refresh of a goal (ADR 0028). Safe to repeat, so it takes no Idempotency-Key; another member's goal is
 * a 404. Kept apart from {@link SavingsController}, whose constructor is already long.
 */
@RestController
@RequestMapping("/api/v1/savings")
class SavingsSyncController {

    private final SyncGoalUseCase sync;
    private final SavingsGoalRepository goals;
    private final ActorAuthentication authentication;

    SavingsSyncController(SyncGoalUseCase sync, SavingsGoalRepository goals, ActorAuthentication authentication) {
        this.sync = sync;
        this.goals = goals;
        this.authentication = authentication;
    }

    @PostMapping("/goals/{id}/sync")
    ResponseEntity<SavingsGoalResponse> syncGoal(@PathVariable("id") UUID id, HttpServletRequest http) {
        Actor caller = authentication.authenticate(http)
            .orElseThrow(() -> new DomainException(CommonErrors.UNAUTHENTICATED, "Sign in to use this route"));
        SavingsGoal goal = goals.findById(id)
            .orElseThrow(() -> new DomainException(SavingsErrors.GOAL_NOT_FOUND, "Savings goal not found"));
        if (caller.type() != Actor.Type.MEMBER || !goal.getMemberId().equalsIgnoreCase(caller.id().toString())) {
            throw new DomainException(SavingsErrors.GOAL_NOT_FOUND, "Savings goal not found");
        }
        return ResponseEntity.ok(SavingsGoalResponse.from(sync.execute(id).goal()));
    }
}
