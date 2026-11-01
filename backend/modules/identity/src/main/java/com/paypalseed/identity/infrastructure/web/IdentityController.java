package com.paypalseed.identity.infrastructure.web;

import com.paypalseed.identity.application.usecases.SessionService;
import com.paypalseed.platform.security.Actor;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own session. */
@RestController
@RequestMapping("/api/v1/auth")
public class IdentityController {

    public record CurrentActor(Actor.Type type, UUID id, Set<Actor.Role> roles, String displayName) {
    }

    private static final String BEARER = "Bearer ";

    private final SessionService sessions;

    public IdentityController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/me")
    CurrentActor me(Actor actor) {
        return new CurrentActor(actor.type(), actor.id(), actor.roles(), sessions.displayName(actor));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(Actor actor, @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith(BEARER)) {
            sessions.revoke(authorization.substring(BEARER.length()));
        }
        return ResponseEntity.noContent().build();
    }
}
