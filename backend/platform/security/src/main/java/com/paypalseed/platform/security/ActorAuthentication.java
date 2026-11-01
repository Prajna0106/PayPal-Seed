package com.paypalseed.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves who is calling, with two credentials that never overlap (ADR 0016):
 *
 * <ul>
 *   <li>the internal service token opens {@code /internal/**} and nothing else;</li>
 *   <li>a signed actor token opens {@code /api/**} as the member or staff member it names, and
 *       nothing else.</li>
 * </ul>
 *
 * A bearer credential that is not valid for the path it arrives on is refused, never reinterpreted.
 * Dev headers ({@code X-Seed-Member-Id}, {@code X-Seed-Staff-*}) let a caller choose any identity, so
 * they are honoured only when {@code seed.security.dev-header-auth=true}, and only when no bearer
 * credential is present (a token always wins). Startup refuses that flag outside dev, test and demo.
 */
@Component
public class ActorAuthentication {

    static final String MEMBER_HEADER = "X-Seed-Member-Id";
    static final String STAFF_HEADER = "X-Seed-Staff-Id";
    static final String STAFF_ROLES_HEADER = "X-Seed-Staff-Roles";
    private static final String BEARER = "Bearer ";
    private static final Logger log = LoggerFactory.getLogger(ActorAuthentication.class);

    private final byte[] internalTokenDigest;
    private final boolean devHeaders;
    private final ActorTokenVerifier actorTokens;

    public ActorAuthentication(@Value("${seed.security.internal-token:}") String internalToken,
                               @Value("${seed.security.dev-header-auth:false}") boolean devHeaders,
                               ActorTokenVerifier actorTokens) {
        // Compare digests so neither the content nor the length of the secret leaks through timing.
        this.internalTokenDigest = internalToken.length() >= 16 ? sha256(internalToken) : null;
        this.devHeaders = devHeaders;
        this.actorTokens = actorTokens;
    }

    public Optional<Actor> authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            return bearer(authorization.substring(BEARER.length()), path(request));
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

    private Optional<Actor> bearer(String credential, String path) {
        if (credential.isEmpty()) {
            return Optional.empty();
        }
        if (internalTokenDigest != null && MessageDigest.isEqual(sha256(credential), internalTokenDigest)) {
            return isUnder(path, "/internal/") ? Optional.of(Actor.service("internal-jobs")) : Optional.empty();
        }
        if (!isUnder(path, "/api/") || !actorTokens.enabled()) {
            return Optional.empty();
        }
        try {
            return Optional.of(actorTokens.verify(credential));
        } catch (ActorTokenException e) {
            // The failure class only: never the token and never its claims, which name a person.
            log.warn("Actor token rejected: {}", e.failure());
            return Optional.empty();
        }
    }

    private static boolean isUnder(String path, String prefix) {
        return path.startsWith(prefix);
    }

    /**
     * The request path as the credential check sees it: no context path, no {@code ;params}, no
     * repeated slashes, dot segments resolved. Anything that does not start with the expected prefix
     * after this is refused, so odd spellings fail closed rather than slip across the boundary.
     */
    static String path(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : uri.split("/")) {
            int params = segment.indexOf(';');
            String clean = params >= 0 ? segment.substring(0, params) : segment;
            if (clean.isEmpty() || clean.equals(".")) {
                continue;
            }
            if (clean.equals("..")) {
                segments.pollLast();
            } else {
                segments.addLast(clean);
            }
        }
        return "/" + String.join("/", segments) + (uri.endsWith("/") && !segments.isEmpty() ? "/" : "");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
