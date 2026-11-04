package com.paypalseed.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves who is calling, with two credentials that never overlap (ADR 0016):
 *
 * <ul>
 *   <li>the internal service token opens {@code /internal/**} and nothing else;</li>
 *   <li>the staff service token, which only the desk and the first-admin script hold, opens the staff login and
 *       account routes and nothing else (ADR 0023);</li>
 *   <li>a signed actor token opens {@code /api/**} as the member or staff member it names, and
 *       nothing else.</li>
 * </ul>
 *
 * A bearer credential that is not valid for the path it arrives on is refused, never reinterpreted.
 * Dev headers ({@code X-Seed-Member-Id}, {@code X-Seed-Staff-*}) let a caller choose any identity, so
 * they are honoured only when {@code seed.security.dev-header-auth=true}, and only when no bearer
 * credential is present (a token always wins). Startup refuses that flag outside dev, test and demo.
 *
 * <p>Staff are then checked against the directory: someone suspended is refused, a role they have lost no longer
 * counts, and a desk session that began before their sessions were ended is refused. Staff the directory doesn't know
 * are refused too, unless dev headers are on.
 */
@Component
public class ActorAuthentication {

    static final String MEMBER_HEADER = "X-Seed-Member-Id";
    static final String STAFF_HEADER = "X-Seed-Staff-Id";
    static final String STAFF_ROLES_HEADER = "X-Seed-Staff-Roles";
    private static final String BEARER = "Bearer ";
    private static final Logger log = LoggerFactory.getLogger(ActorAuthentication.class);
    private static final UUID STAFF_ACCOUNTS_ID = UUID.nameUUIDFromBytes("staff-accounts".getBytes(StandardCharsets.UTF_8));

    private record Credential(Actor actor, Instant authTime, boolean signed) {
    }

    private final byte[] internalTokenDigest;
    private final byte[] staffServiceTokenDigest;
    private final boolean devHeaders;
    private final ActorTokenVerifier actorTokens;
    private final ObjectProvider<StaffAccessLookup> staffDirectory;

    public ActorAuthentication(String internalToken, boolean devHeaders, ActorTokenVerifier actorTokens,
                               ObjectProvider<StaffAccessLookup> staffDirectory) {
        this(internalToken, "", devHeaders, actorTokens, staffDirectory);
    }

    @Autowired
    public ActorAuthentication(@Value("${seed.security.internal-token:}") String internalToken,
                               @Value("${seed.security.staff-service-token:}") String staffServiceToken,
                               @Value("${seed.security.dev-header-auth:false}") boolean devHeaders,
                               ActorTokenVerifier actorTokens,
                               ObjectProvider<StaffAccessLookup> staffDirectory) {
        if (!staffServiceToken.isEmpty() && staffServiceToken.equals(internalToken)) {
            throw new IllegalStateException("seed.security.staff-service-token must differ from seed.security.internal-token");
        }
        // Compare digests so neither the content nor the length of the secret leaks through timing.
        this.internalTokenDigest = internalToken.length() >= 16 ? sha256(internalToken) : null;
        this.staffServiceTokenDigest = staffServiceToken.length() >= 16 ? sha256(staffServiceToken) : null;
        this.devHeaders = devHeaders;
        this.actorTokens = actorTokens;
        this.staffDirectory = staffDirectory;
    }

    public Optional<Actor> authenticate(HttpServletRequest request) {
        return credentialActor(request).flatMap(this::withCurrentAccess);
    }

    private Optional<Actor> withCurrentAccess(Credential credential) {
        Actor actor = credential.actor();
        if (actor.type() != Actor.Type.STAFF) {
            return Optional.of(actor);
        }
        StaffAccessLookup directory = staffDirectory == null ? null : staffDirectory.getIfAvailable();
        Optional<StaffAccessLookup.StaffAccess> found = directory == null ? Optional.empty() : directory.access(actor.id());
        if (found.isEmpty()) {
            return devHeaders ? Optional.of(actor) : refused("UNKNOWN_STAFF");
        }
        StaffAccessLookup.StaffAccess access = found.get();
        if (!access.active()) {
            return refused("SUSPENDED");
        }
        if (credential.signed() && access.sessionsValidAfter() != null
                && (credential.authTime() == null || credential.authTime().isBefore(access.sessionsValidAfter()))) {
            return refused("SESSION_ENDED");
        }
        Set<Actor.Role> roles = EnumSet.noneOf(Actor.Role.class);
        if (!access.passwordChangeRequired()) {
            actor.roles().stream().filter(access.roles()::contains).forEach(roles::add);
        }
        return Optional.of(new Actor(Actor.Type.STAFF, actor.id(), roles, access.linkedMemberId()));
    }

    private static Optional<Actor> refused(String reason) {
        log.warn("Staff credential rejected: {}", reason);
        return Optional.empty();
    }

    private Optional<Credential> credentialActor(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            return bearer(authorization.substring(BEARER.length()), path(request));
        }
        if (!devHeaders) {
            return Optional.empty();
        }
        String member = request.getHeader(MEMBER_HEADER);
        if (member != null) {
            return parseUuid(member).map(Actor::member).map(ActorAuthentication::unsigned);
        }
        String staff = request.getHeader(STAFF_HEADER);
        if (staff != null) {
            Set<Actor.Role> roles = EnumSet.noneOf(Actor.Role.class);
            String header = Optional.ofNullable(request.getHeader(STAFF_ROLES_HEADER)).orElse("");
            for (String r : header.split(",")) {
                if (!r.isBlank()) {
                    try {
                        Actor.Role role = Actor.Role.valueOf(r.trim().toUpperCase(java.util.Locale.ROOT));
                        if (role != Actor.Role.SERVICE && role != Actor.Role.MEMBER && role != Actor.Role.STAFF_ACCOUNTS) {
                            roles.add(role);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // unknown roles grant nothing
                    }
                }
            }
            return parseUuid(staff).map(id -> new Actor(Actor.Type.STAFF, id, roles)).map(ActorAuthentication::unsigned);
        }
        return Optional.empty();
    }

    private static Credential unsigned(Actor actor) {
        return new Credential(actor, null, false);
    }

    private Optional<Credential> bearer(String credential, String path) {
        if (credential.isEmpty()) {
            return Optional.empty();
        }
        byte[] digest = sha256(credential);
        if (staffServiceTokenDigest != null && MessageDigest.isEqual(digest, staffServiceTokenDigest)) {
            return isUnder(path, "/internal/auth/staff/") || isUnder(path, "/internal/staff/")
                    ? Optional.of(unsigned(new Actor(Actor.Type.SERVICE, STAFF_ACCOUNTS_ID, Set.of(Actor.Role.STAFF_ACCOUNTS))))
                    : Optional.empty();
        }
        if (internalTokenDigest != null && MessageDigest.isEqual(digest, internalTokenDigest)) {
            return isUnder(path, "/internal/") ? Optional.of(unsigned(Actor.service("internal-jobs"))) : Optional.empty();
        }
        if (!isUnder(path, "/api/") || !actorTokens.enabled()) {
            return Optional.empty();
        }
        try {
            ActorTokenVerifier.Verified verified = actorTokens.verifyWithSession(credential);
            return Optional.of(new Credential(verified.actor(), verified.authTime(), true));
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
