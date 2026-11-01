package com.paypalseed.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mints actor tokens the way the web servers do (ADR 0016). The backend never mints tokens at
 * runtime; this is for tests and the {@code mintActorToken} dev task, and its output is the
 * reference the Node implementation is compared with.
 */
public final class ActorTokenIssuer {

    /** Short enough that a leaked token is nearly useless, long enough to cover one page render. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final String kid;
    private final byte[] secret;
    private final Clock clock;

    public ActorTokenIssuer(String kid, String secret, Clock clock) {
        this.kid = kid;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** Signs with the first key of {@code keys} (the rotation rule: the newest key signs). */
    public static ActorTokenIssuer signingWith(ActorTokenKeys keys, Clock clock) {
        byte[] secret = keys.secret(keys.signingKid());
        return new ActorTokenIssuer(keys.signingKid(), new String(secret, StandardCharsets.UTF_8), clock);
    }

    public String member(UUID memberId) {
        return member(memberId, DEFAULT_TTL);
    }

    public String member(UUID memberId, Duration ttl) {
        return sign(memberId, "member", null, ttl);
    }

    public String staff(UUID staffId, Set<Actor.Role> roles) {
        return staff(staffId, roles, DEFAULT_TTL);
    }

    public String staff(UUID staffId, Set<Actor.Role> roles, Duration ttl) {
        List<String> names = roles.stream().map(Enum::name).sorted().toList();
        return sign(staffId, "staff", names, ttl);
    }

    private String sign(UUID subject, String actorType, List<String> roles, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(ActorTokenVerifier.MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("ttl must be between 1 second and 5 minutes");
        }
        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ActorTokenVerifier.ISSUER)
                .audience(ActorTokenVerifier.AUDIENCE)
                .subject(subject.toString())
                .claim("act", actorType)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString());
        if (roles != null) {
            claims.claim("roles", roles);
        }
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID(kid).build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the actor token", e);
        }
        return jwt.serialize();
    }
}
