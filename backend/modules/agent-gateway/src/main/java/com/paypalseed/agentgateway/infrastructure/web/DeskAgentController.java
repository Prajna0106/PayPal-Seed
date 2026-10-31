package com.paypalseed.agentgateway.infrastructure.web;

import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.KillSwitch.Mode;
import com.paypalseed.agentgateway.application.usecases.KillSwitchService;
import com.paypalseed.agentgateway.domain.model.AgentErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The desk's kill switch. Any officer may make agents safer at once; only an admin may move a scope back
 * to a less restrictive mode, with a reason, and every change is in the append-only history.
 */
@RestController
class DeskAgentController {

    record ChangeRequest(@NotBlank @Pattern(regexp = "GLOBAL|[a-z][a-z0-9-]{1,40}") String scope, @NotNull Mode mode,
                         @NotBlank @Size(max = 500) String reason) {
    }

    private final KillSwitchService killSwitch;

    DeskAgentController(KillSwitchService killSwitch) {
        this.killSwitch = killSwitch;
    }

    @GetMapping("/api/v1/desk/agents/kill-switch")
    KillSwitch.State state(Actor actor, @RequestParam Optional<String> agent) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return killSwitch.state(agent.orElse("guardian"));
    }

    @PostMapping("/api/v1/desk/agents/kill-switch")
    KillSwitch.State change(Actor actor, @Valid @RequestBody ChangeRequest body) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        Mode current = killSwitch.scopeMode(body.scope());
        if (current.moreRestrictiveThan(body.mode()) && !actor.roles().contains(Role.ADMIN)) {
            throw new DomainException(AgentErrors.KILL_SWITCH_UP_NEEDS_ADMIN,
                    "Moving " + body.scope() + " from " + current + " to " + body.mode() + " needs an admin");
        }
        killSwitch.set(body.scope(), body.mode(), body.reason(), "staff:" + actor.id());
        return killSwitch.state(KillSwitch.GLOBAL.equals(body.scope()) ? "guardian" : body.scope());
    }
}
