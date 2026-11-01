package com.paypalseed.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Verifies the signed token the Next.js servers send to prove who the actor is (ADR 0016).
 *
 * <p>The algorithm is fixed to HS256 here, never read from the token: a header that says anything
 * else (none, HS384/512, RS*, ES*) is refused before any key is touched, which closes the
 * alg-none and key-confusion attacks. The header may carry only {@code alg}, {@code typ} and
 * {@code kid}. Claims are looked at only after the signature checked out.
 */
public final class ActorTokenVerifier {

    public static final String ISSUER = "seed-web";
    public static final String AUDIENCE = "seed-api";
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(5);

    private static final int MAX_TOKEN_CHARS = 4096;
    private static final int MAX_ROLES = 8;
    private static final Set<String> ALLOWED_HEADER_PARAMS = Set.of("alg", "typ", "kid");
    private static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** Roles a staff token may never carry: they belong to other credentials. */
    private static final Set<Actor.Role> NOT_FOR_STAFF_TOKENS =
            EnumSet.of(Actor.Role.MEMBER, Actor.Role.SERVICE, Actor.Role.AGENT_READ);

    private final ActorTokenKeys keys;
    private final Duration clockSkew;
    private final Clock clock;

    /** @param clock wall clock: token lifetimes are real time, not the business date */
    public ActorTokenVerifier(ActorTokenKeys keys, Duration clockSkew, Clock clock) {
        if (clockSkew.isNegative() || clockSkew.compareTo(ActorTokenSettings.MAX_SKEW) > 0) {
            throw new IllegalArgumentException("clock skew must be between 0 and 30 seconds");
        }
        this.keys = keys;
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    /** False when no secret is configured (dev without tokens): every token is then refused. */
    public boolean enabled() {
        return !keys.isEmpty();
    }

    /** Returns the actor the token proves, or throws {@link ActorTokenException} naming the failed check. */
    public Actor verify(String token) {
        if (keys.isEmpty()) {
            throw new ActorTokenException(ActorTokenFailure.NOT_CONFIGURED);
        }
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
            throw new ActorTokenException(ActorTokenFailure.MALFORMED);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new ActorTokenException(ActorTokenFailure.MALFORMED);
        }
        String kid = checkHeader(parts[0]);
        byte[] secret = secretFor(kid);
        SignedJWT jwt = parse(token);
        verifySignature(jwt, secret);
        return actorFrom(claims(jwt));
    }

    /** Returns the {@code kid} header (null when absent) after the fixed-algorithm checks. */
    private static String checkHeader(String encodedHeader) {
        Map<String, Object> header;
        try {
            header = JSONObjectUtils.parse(Base64URL.from(encodedHeader).decodeToString());
        } catch (ParseException | RuntimeException e) {
            throw new ActorTokenException(ActorTokenFailure.MALFORMED);
        }
        if (!"HS256".equals(header.get("alg"))) {
            throw new ActorTokenException(ActorTokenFailure.ALGORITHM);
        }
        if (!ALLOWED_HEADER_PARAMS.containsAll(header.keySet()) || !"JWT".equals(header.get("typ"))) {
            throw new ActorTokenException(ActorTokenFailure.HEADER);
        }
        Object kid = header.get("kid");
        if (kid != null && !(kid instanceof String k && KID.matcher(k).matches())) {
            throw new ActorTokenException(ActorTokenFailure.UNKNOWN_KEY);
        }
        return (String) kid;
    }

    /** A kid must be known; no kid is accepted only while a single key is configured (no rotation). */
    private byte[] secretFor(String kid) {
        byte[] secret = kid == null ? keys.soleSecret() : keys.secret(kid);
        if (secret == null) {
            throw new ActorTokenException(ActorTokenFailure.UNKNOWN_KEY);
        }
        return secret;
    }

    private static SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new ActorTokenException(ActorTokenFailure.MALFORMED);
        }
    }

    private static void verifySignature(SignedJWT jwt, byte[] secret) {
        try {
            // MACVerifier compares the MAC in constant time.
            if (!jwt.verify(new MACVerifier(secret))) {
                throw new ActorTokenException(ActorTokenFailure.SIGNATURE);
            }
        } catch (JOSEException e) {
            throw new ActorTokenException(ActorTokenFailure.SIGNATURE);
        }
    }

    private static JWTClaimsSet claims(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new ActorTokenException(ActorTokenFailure.MALFORMED);
        }
    }

    private Actor actorFrom(JWTClaimsSet claims) {
        if (!ISSUER.equals(claims.getIssuer())) {
            throw new ActorTokenException(ActorTokenFailure.ISSUER);
        }
        if (!List.of(AUDIENCE).equals(claims.getAudience())) {
            throw new ActorTokenException(ActorTokenFailure.AUDIENCE);
        }
        UUID subject = subject(claims.getSubject());
        Object type = claims.getClaim("act");
        if (!"member".equals(type) && !"staff".equals(type)) {
            throw new ActorTokenException(ActorTokenFailure.ACTOR_TYPE);
        }
        checkTiming(claims);
        String jti = claims.getJWTID();
        if (jti == null || jti.isBlank() || jti.length() > 128) {
            throw new ActorTokenException(ActorTokenFailure.MISSING_CLAIM);
        }
        if ("member".equals(type)) {
            // A roles claim on a member token is ignored: members hold Role.MEMBER and nothing else.
            return Actor.member(subject);
        }
        return new Actor(Actor.Type.STAFF, subject, staffRoles(claims.getClaim("roles")));
    }

    private static UUID subject(String sub) {
        if (sub == null || !UUID_TEXT.matcher(sub).matches()) {
            throw new ActorTokenException(ActorTokenFailure.SUBJECT);
        }
        return UUID.fromString(sub);
    }

    private void checkTiming(JWTClaimsSet claims) {
        Date exp = claims.getExpirationTime();
        Date iat = claims.getIssueTime();
        if (exp == null || iat == null) {
            throw new ActorTokenException(ActorTokenFailure.MISSING_CLAIM);
        }
        Instant expiresAt = exp.toInstant();
        Instant issuedAt = iat.toInstant();
        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new ActorTokenException(ActorTokenFailure.LIFETIME);
        }
        Instant now = clock.instant();
        if (now.isAfter(expiresAt.plus(clockSkew))) {
            throw new ActorTokenException(ActorTokenFailure.EXPIRED);
        }
        Date nbf = claims.getNotBeforeTime();
        if (issuedAt.isAfter(now.plus(clockSkew)) || (nbf != null && nbf.toInstant().isAfter(now.plus(clockSkew)))) {
            throw new ActorTokenException(ActorTokenFailure.NOT_YET_VALID);
        }
    }

    /**
     * A staff token with no roles claim is a valid actor with no powers (desk routes answer 403). An
     * unknown or disallowed role refuses the whole token: a signed token naming a role we do not
     * have is a bug or an attack, and failing closed makes it visible.
     */
    private static Set<Actor.Role> staffRoles(Object claim) {
        if (claim == null) {
            return Set.of();
        }
        if (!(claim instanceof List<?> list) || list.size() > MAX_ROLES) {
            throw new ActorTokenException(ActorTokenFailure.ROLES);
        }
        Set<Actor.Role> roles = EnumSet.noneOf(Actor.Role.class);
        for (Object item : list) {
            if (!(item instanceof String name)) {
                throw new ActorTokenException(ActorTokenFailure.ROLES);
            }
            Actor.Role role;
            try {
                role = Actor.Role.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new ActorTokenException(ActorTokenFailure.ROLES);
            }
            if (NOT_FOR_STAFF_TOKENS.contains(role)) {
                throw new ActorTokenException(ActorTokenFailure.ROLES);
            }
            roles.add(role);
        }
        return roles;
    }
}
