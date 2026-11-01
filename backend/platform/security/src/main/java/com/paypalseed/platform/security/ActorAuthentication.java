package com.paypalseed.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bearer internal token (compared in constant time) resolves to the SERVICE actor; any other bearer token
 * is a signed-in user's session, checked by the {@link ActorTokenResolver}. Dev headers
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
    private final Supplier<ActorTokenResolver> tokens;

    /** Without a token resolver: only the internal token and dev headers can authenticate. */
    public ActorAuthentication(String internalToken, boolean devHeaders) {
        this(internalToken, devHeaders, () -> null);
    }

    @Autowired
    public ActorAuthentication(@Value("${seed.security.internal-token:}") String internalToken,
                               @Value("${seed.security.dev-header-auth:false}") boolean devHeaders,
                               ObjectProvider<ActorTokenResolver> tokens) {
        this(internalToken, devHeaders, (Supplier<ActorTokenResolver>) tokens::getIfAvailable);
    }

    private ActorAuthentication(String internalToken, boolean devHeaders, Supplier<ActorTokenResolver> tokens) {
        this.internalToken = internalToken.getBytes(StandardCharsets.UTF_8);
        this.devHeaders = devHeaders;
        this.tokens = tokens;
    }

    public Optional<Actor> authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String bearer = authorization.substring("Bearer ".length());
            if (internalToken.length >= 16
                    && MessageDigest.isEqual(bearer.getBytes(StandardCharsets.UTF_8), internalToken)) {
                return Optional.of(Actor.service("internal-jobs"));
            }
            // A bad bearer token is a failed sign-in: it never falls through to the dev headers.
            ActorTokenResolver resolver = tokens.get();
            return resolver != null ? resolver.resolve(bearer) : Optional.empty();
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
