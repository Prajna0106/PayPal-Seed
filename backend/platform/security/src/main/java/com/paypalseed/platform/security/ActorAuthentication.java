package com.paypalseed.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bearer internal token (compared in constant time) resolves to the SERVICE actor. Dev headers
 * ({@code X-Seed-Member-Id}, {@code X-Seed-Staff-*}) let a caller choose any identity, so they are
 * honoured only when {@code seed.security.dev-header-auth=true}. Never enable that in production.
 */
@Component
public class ActorAuthentication {

    static final String MEMBER_HEADER = "X-Seed-Member-Id";
    static final String STAFF_HEADER = "X-Seed-Staff-Id";
    static final String STAFF_ROLES_HEADER = "X-Seed-Staff-Roles";

    private final byte[] internalToken;
    private final boolean devHeaders;

    public ActorAuthentication(@Value("${seed.security.internal-token:}") String internalToken,
                               @Value("${seed.security.dev-header-auth:false}") boolean devHeaders) {
        this.internalToken = internalToken.getBytes(StandardCharsets.UTF_8);
        this.devHeaders = devHeaders;
    }

    public Optional<Actor> authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ") && internalToken.length >= 16) {
            byte[] presented = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(presented, internalToken)) {
                return Optional.of(Actor.service("internal-jobs"));
            }
            return Optional.empty();
        }
        if (!devHeaders) {
            return Optional.empty();
        }
        String member = request.getHeader(MEMBER_HEADER);
        if (member != null) {
            return parseUuid(member).map(Actor::member);
        }
        String staff = request.getHeader(STAFF_HEADER);
        if (staff != null) {
            Set<Actor.Role> roles = EnumSet.noneOf(Actor.Role.class);
            String header = Optional.ofNullable(request.getHeader(STAFF_ROLES_HEADER)).orElse("");
            for (String r : header.split(",")) {
                if (!r.isBlank()) {
                    try {
                        Actor.Role role = Actor.Role.valueOf(r.trim().toUpperCase(java.util.Locale.ROOT));
                        if (role != Actor.Role.SERVICE && role != Actor.Role.MEMBER) {
                            roles.add(role);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // unknown roles grant nothing
                    }
                }
            }
            return parseUuid(staff).map(id -> new Actor(Actor.Type.STAFF, id, roles));
        }
        return Optional.empty();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
