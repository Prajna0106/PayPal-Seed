package com.paypalseed.identity.application.usecases;

import com.paypalseed.identity.infrastructure.persistence.IdentityRepository;
import com.paypalseed.identity.infrastructure.persistence.IdentityRepository.StoredSession;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.ActorTokenResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Issues and checks sign-in sessions. The token is {@code seedses_} plus 256 random bits (the prefix lets
 * secret scanners spot a leaked one); only its SHA-256 is stored. A session ends at its maximum lifetime,
 * after an idle gap, on logout, or as soon as the user is suspended.
 */
@Service
public class SessionService implements ActorTokenResolver {

    public record IssuedSession(String token, Instant expiresAt) {
    }

    static final String PREFIX = "seedses_";
    static final Duration MEMBER_IDLE = Duration.ofMinutes(30);
    static final Duration MEMBER_MAX = Duration.ofHours(12);
    static final Duration STAFF_IDLE = Duration.ofMinutes(15);
    static final Duration STAFF_MAX = Duration.ofHours(8);
    /** Last-seen is coarse on purpose, so an ordinary read doesn't write to the database every time. */
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private static final String MEMBER = "MEMBER";
    private static final String STAFF = "STAFF";

    private final IdentityRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public SessionService(IdentityRepository repository) {
        this(repository, Clock.systemUTC());
    }

    SessionService(IdentityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public IssuedSession issueForMember(UUID memberId) {
        return issue(MEMBER, memberId, MEMBER_MAX);
    }

    public IssuedSession issueForStaff(UUID staffId) {
        return issue(STAFF, staffId, STAFF_MAX);
    }

    @Override
    public Optional<Actor> resolve(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 128) {
            return Optional.empty();
        }
        String hash = hash(token);
        Instant now = clock.instant();
        Optional<StoredSession> session = repository.findSession(hash).filter(s -> isLive(s, now));
        Optional<Actor> actor = session.flatMap(this::actorOf);
        if (actor.isPresent() && !now.isBefore(session.get().lastSeenAt().plus(TOUCH_EVERY))) {
            repository.touchSession(hash, now.truncatedTo(ChronoUnit.MICROS));
        }
        return actor;
    }

    public void revoke(String token) {
        if (token != null && token.startsWith(PREFIX) && token.length() <= 128) {
            repository.revokeSession(hash(token));
        }
    }

    public String displayName(Actor actor) {
        return actor.type() == Actor.Type.MEMBER ? repository.memberDisplayName(actor.id()).orElse(null) : null;
    }

    private IssuedSession issue(String actorType, UUID actorId, Duration lifetime) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant authTime = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = authTime.plus(lifetime);
        repository.insertSession(hash(token), actorType, actorId, authTime, expiresAt);
        return new IssuedSession(token, expiresAt);
    }

    private static boolean isLive(StoredSession session, Instant now) {
        Duration idle = MEMBER.equals(session.actorType()) ? MEMBER_IDLE : STAFF_IDLE;
        return session.revokedAt() == null
                && now.isBefore(session.expiresAt())
                && now.isBefore(session.lastSeenAt().plus(idle));
    }

    private Optional<Actor> actorOf(StoredSession session) {
        UUID id = session.actorId();
        if (MEMBER.equals(session.actorType())) {
            return repository.memberIsActive(id) ? Optional.of(Actor.member(id)) : Optional.empty();
        }
        return repository.activeStaffRoles(id).map(roles -> new Actor(Actor.Type.STAFF, id, staffRoles(roles)));
    }

    private static Set<Actor.Role> staffRoles(Set<String> stored) {
        Set<Actor.Role> roles = EnumSet.noneOf(Actor.Role.class);
        for (String role : stored) {
            roles.add(Actor.Role.valueOf(role));
        }
        return roles;
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
