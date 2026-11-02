package com.paypalseed.fraud.infrastructure.web;

import com.paypalseed.fraud.api.FraudFacade;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagDetail;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagEvidence;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagPage;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagStatus;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagView;
import com.paypalseed.fraud.api.dto.FraudDtos.ReviewDecision;
import com.paypalseed.fraud.domain.model.FraudErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The officer desk for fraud flags. Every route names the roles it needs; members and services get 403. */
@RestController
@Validated
class FraudController {

    record ReviewBody(@NotNull ReviewDecision decision, @Size(max = 1000) String note) {
    }

    private final FraudFacade fraud;

    FraudController(FraudFacade fraud) {
        this.fraud = fraud;
    }

    @GetMapping("/api/v1/desk/fraud/flags")
    FlagPage list(Actor actor, @RequestParam(required = false) List<FlagStatus> status,
                  @RequestParam(required = false) UUID before, @RequestParam(defaultValue = "50") int limit) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return fraud.flags(status == null ? List.of() : status, before, limit);
    }

    @GetMapping("/api/v1/desk/fraud/flags/{flagId}")
    FlagDetail detail(Actor actor, @PathVariable UUID flagId) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return fraud.flag(flagId).orElseThrow(() -> notFound(flagId));
    }

    @PostMapping("/api/v1/desk/fraud/flags/{flagId}/review")
    FlagView review(Actor actor, @PathVariable UUID flagId, @Valid @RequestBody ReviewBody body,
                    @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        actor.requireAnyRole(Role.OFFICER, Role.ADMIN);
        return fraud.review(flagId, body.decision(), body.note(), actor.id(), key);
    }

    @GetMapping("/api/v1/desk/fraud/flags/{flagId}/evidence")
    FlagEvidence evidence(Actor actor, @PathVariable UUID flagId) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return fraud.evidence(flagId).orElseThrow(() -> notFound(flagId));
    }

    private static DomainException notFound(UUID flagId) {
        return new DomainException(FraudErrors.FLAG_NOT_FOUND, "No flag " + flagId);
    }
}
