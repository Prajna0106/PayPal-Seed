package com.paypalseed.platform.security;

import static com.paypalseed.platform.security.TokenForge.NOW;
import static com.paypalseed.platform.security.TokenForge.SECRET_1;
import static com.paypalseed.platform.security.TokenForge.SECRET_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActorTokenVerifierTest {

    private static final UUID ID = UUID.fromString("0b0e7a52-6f0a-4b55-a1a4-3c1d1f5c9a01");

    private final ActorTokenVerifier verifier = verifier(SECRET_1);

    private static ActorTokenVerifier verifier(String... secrets) {
        String[] pairs = new String[secrets.length];
        for (int i = 0; i < secrets.length; i++) {
            pairs[i] = "k" + (i + 1) + ":" + secrets[i];
        }
        return new ActorTokenVerifier(ActorTokenKeys.parse(List.of(pairs)), Duration.ofSeconds(30),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String token(Map<String, Object> claims) {
        return TokenForge.sign(claims, "k1", SECRET_1);
    }

    private void assertRejected(ActorTokenVerifier v, String token, ActorTokenFailure expected) {
        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOfSatisfying(ActorTokenException.class, e -> assertThat(e.failure()).isEqualTo(expected))
                .hasMessage(expected.name());
    }

    private void assertRejected(String token, ActorTokenFailure expected) {
        assertRejected(verifier, token, expected);
    }

    // ---- accepted

    @Test
    void memberTokenResolvesToMember() {
        Actor actor = verifier.verify(token(TokenForge.member(ID)));
        assertThat(actor).isEqualTo(Actor.member(ID));
    }

    @Test
    void staffTokenCarriesItsRoles() {
        Actor actor = verifier.verify(token(TokenForge.staff(ID, "OFFICER", "CHECKER")));
        assertThat(actor.type()).isEqualTo(Actor.Type.STAFF);
        assertThat(actor.id()).isEqualTo(ID);
        assertThat(actor.roles()).containsExactlyInAnyOrder(Actor.Role.OFFICER, Actor.Role.CHECKER);
    }

    @Test
    void staffTokenWithoutRolesIsAnActorWithNoPowers() {
        Map<String, Object> claims = TokenForge.staff(ID);
        claims.remove("roles");
        assertThat(verifier.verify(token(claims)).roles()).isEmpty();
        assertThat(verifier.verify(token(TokenForge.staff(ID))).roles()).isEmpty();
    }

    @Test
    void rolesClaimOnAMemberTokenIsIgnored() {
        Map<String, Object> claims = TokenForge.member(ID);
        claims.put("roles", List.of("ADMIN", "SERVICE", "NOT_A_ROLE"));
        Actor actor = verifier.verify(token(claims));
        assertThat(actor.type()).isEqualTo(Actor.Type.MEMBER);
        assertThat(actor.roles()).containsExactly(Actor.Role.MEMBER);
    }

    @Test
    void issuerHelperTokensVerify() {
        ActorTokenKeys keys = ActorTokenKeys.parse(List.of("k1:" + SECRET_1));
        ActorTokenIssuer issuer = ActorTokenIssuer.signingWith(keys, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(verifier.verify(issuer.member(ID))).isEqualTo(Actor.member(ID));
        assertThat(verifier.verify(issuer.staff(ID, Set.of(Actor.Role.ADMIN))).roles()).containsExactly(Actor.Role.ADMIN);
        assertThatThrownBy(() -> issuer.member(ID, Duration.ofMinutes(6))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- time

    @Test
    void expiredTokenIsRefusedButClockSkewIsTolerated() {
        Map<String, Object> claims = TokenForge.member(ID);
        claims.put("iat", NOW.minusSeconds(120).getEpochSecond());
        claims.put("exp", NOW.minusSeconds(31).getEpochSecond());
        assertRejected(token(claims), ActorTokenFailure.EXPIRED);

        claims.put("exp", NOW.minusSeconds(10).getEpochSecond());
        assertThat(verifier.verify(token(claims))).isEqualTo(Actor.member(ID));
    }

    @Test
    void tokenIssuedInTheFutureIsRefusedButSmallSkewIsTolerated() {
        Map<String, Object> claims = TokenForge.member(ID);
        claims.put("iat", NOW.plusSeconds(60).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(120).getEpochSecond());
        assertRejected(token(claims), ActorTokenFailure.NOT_YET_VALID);

        claims.put("iat", NOW.plusSeconds(20).getEpochSecond());
        assertThat(verifier.verify(token(claims))).isEqualTo(Actor.member(ID));
    }

    @Test
    void notBeforeInTheFutureIsRefused() {
        Map<String, Object> claims = TokenForge.member(ID);
        claims.put("nbf", NOW.plusSeconds(120).getEpochSecond());
        assertRejected(token(claims), ActorTokenFailure.NOT_YET_VALID);
    }

    @Test
    void lifetimeOverFiveMinutesIsRefused() {
        Map<String, Object> claims = TokenForge.member(ID);
        claims.put("exp", NOW.plusSeconds(301).getEpochSecond());
        assertRejected(token(claims), ActorTokenFailure.LIFETIME);

        claims.put("exp", NOW.plusSeconds(300).getEpochSecond());
        assertThat(verifier.verify(token(claims))).isEqualTo(Actor.member(ID));

        claims.put("exp", NOW.getEpochSecond());
        assertRejected(token(claims), ActorTokenFailure.LIFETIME);
    }

    @Test
    void missingTimeOrIdClaimsAreRefused() {
        for (String claim : List.of("exp", "iat", "jti")) {
            Map<String, Object> claims = TokenForge.member(ID);
            claims.remove(claim);
            assertRejected(token(claims), ActorTokenFailure.MISSING_CLAIM);
        }
        Map<String, Object> blank = TokenForge.member(ID);
        blank.put("jti", " ");
        assertRejected(token(blank), ActorTokenFailure.MISSING_CLAIM);
    }

    // ---- claims

    @Test
    void wrongIssuerIsRefused() {
        Map<String, Object> claims = TokenForge.member(ID);
        claims.put("iss", "someone-else");
        assertRejected(token(claims), ActorTokenFailure.ISSUER);
        claims.remove("iss");
        assertRejected(token(claims), ActorTokenFailure.ISSUER);
    }

    @Test
    void wrongAudienceIsRefused() {
        Map<String, Object> claims = TokenForge.member(ID);
        claims.put("aud", "seed-internal");
        assertRejected(token(claims), ActorTokenFailure.AUDIENCE);
        claims.put("aud", List.of("seed-api", "another-service"));
        assertRejected(token(claims), ActorTokenFailure.AUDIENCE);
        claims.remove("aud");
        assertRejected(token(claims), ActorTokenFailure.AUDIENCE);
    }

    @Test
    void missingOrNonUuidSubjectIsRefused() {
        Map<String, Object> claims = TokenForge.member(ID);
        claims.remove("sub");
        assertRejected(token(claims), ActorTokenFailure.SUBJECT);
        for (String bad : List.of("member-42", "", "1-1-1-1-1", ID + " ", "{" + ID + "}")) {
            claims.put("sub", bad);
            assertRejected(token(claims), ActorTokenFailure.SUBJECT);
        }
    }

    @Test
    void unknownActorTypeIsRefused() {
        for (Object bad : List.of("admin", "service", "MEMBER", "")) {
            Map<String, Object> claims = TokenForge.member(ID);
            claims.put("act", bad);
            assertRejected(token(claims), ActorTokenFailure.ACTOR_TYPE);
        }
        Map<String, Object> claims = TokenForge.member(ID);
        claims.remove("act");
        assertRejected(token(claims), ActorTokenFailure.ACTOR_TYPE);
    }

    @Test
    void unknownOrDisallowedRoleRefusesTheWholeToken() {
        assertRejected(token(TokenForge.staff(ID, "OFFICER", "SUPERUSER")), ActorTokenFailure.ROLES);
        assertRejected(token(TokenForge.staff(ID, "officer")), ActorTokenFailure.ROLES);
        assertRejected(token(TokenForge.staff(ID, "SERVICE")), ActorTokenFailure.ROLES);
        assertRejected(token(TokenForge.staff(ID, "MEMBER")), ActorTokenFailure.ROLES);
        assertRejected(token(TokenForge.staff(ID, "AGENT_READ")), ActorTokenFailure.ROLES);

        Map<String, Object> notAList = TokenForge.staff(ID);
        notAList.put("roles", "OFFICER");
        assertRejected(token(notAList), ActorTokenFailure.ROLES);

        Map<String, Object> notStrings = TokenForge.staff(ID);
        notStrings.put("roles", List.of(1, 2));
        assertRejected(token(notStrings), ActorTokenFailure.ROLES);

        Map<String, Object> tooMany = TokenForge.staff(ID);
        tooMany.put("roles", List.of("OFFICER", "OFFICER", "OFFICER", "OFFICER", "OFFICER", "OFFICER", "OFFICER",
                "OFFICER", "OFFICER"));
        assertRejected(token(tooMany), ActorTokenFailure.ROLES);
    }

    // ---- signature and algorithm

    @Test
    void wrongSignatureIsRefused() {
        assertRejected(TokenForge.sign(TokenForge.member(ID), "k1", SECRET_2), ActorTokenFailure.SIGNATURE);
    }

    @Test
    void tamperedPayloadIsRefused() {
        String[] parts = token(TokenForge.member(ID)).split("\\.");
        Map<String, Object> forged = TokenForge.staff(ID, "ADMIN");
        String tampered = parts[0] + "." + TokenForge.encode(com.nimbusds.jose.util.JSONObjectUtils.toJSONString(forged))
                + "." + parts[2];
        assertRejected(tampered, ActorTokenFailure.SIGNATURE);
    }

    @Test
    void truncatedOrAlteredSignatureIsRefused() {
        String valid = token(TokenForge.member(ID));
        assertRejected(valid.substring(0, valid.length() - 4), ActorTokenFailure.SIGNATURE);
        char last = valid.charAt(valid.length() - 1);
        assertRejected(valid.substring(0, valid.length() - 1) + (last == 'A' ? 'B' : 'A'), ActorTokenFailure.SIGNATURE);
    }

    @Test
    void algNoneIsRefused() {
        assertRejected(TokenForge.unsigned(TokenForge.staff(ID, "ADMIN")), ActorTokenFailure.ALGORITHM);
        assertRejected(TokenForge.withHeader("{\"alg\":\"None\",\"typ\":\"JWT\"}", TokenForge.member(ID), SECRET_1),
                ActorTokenFailure.ALGORITHM);
    }

    @Test
    void otherHmacSizesAreRefusedWhenHs256IsFixed() {
        String hs512 = TokenForge.sign(JWSAlgorithm.HS512, JOSEObjectType.JWT, "k1", TokenForge.member(ID), SECRET_1);
        assertRejected(hs512, ActorTokenFailure.ALGORITHM);
        String hs384 = TokenForge.sign(JWSAlgorithm.HS384, JOSEObjectType.JWT, "k1", TokenForge.member(ID), SECRET_1);
        assertRejected(hs384, ActorTokenFailure.ALGORITHM);
    }

    @Test
    void asymmetricAlgorithmsAreRefusedKeyConfusionIncluded() {
        // The classic attack: sign with the HMAC key but claim RS256/ES256 so a verifier that trusts the header
        // would treat the key material as a public key.
        for (String alg : List.of("RS256", "RS512", "ES256", "PS256", "EdDSA")) {
            String forged = TokenForge.withHeader("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\",\"kid\":\"k1\"}",
                    TokenForge.member(ID), SECRET_1);
            assertRejected(forged, ActorTokenFailure.ALGORITHM);
        }
        assertRejected(TokenForge.withHeader("{\"typ\":\"JWT\",\"kid\":\"k1\"}", TokenForge.member(ID), SECRET_1),
                ActorTokenFailure.ALGORITHM);
    }

    @Test
    void headerMustBePlainJwtWithoutKeyLookupTricks() {
        for (String extra : List.of("\"jku\":\"https://evil.example/keys\"", "\"jwk\":{\"kty\":\"oct\",\"k\":\"AAAA\"}",
                "\"x5u\":\"https://evil.example/cert\"", "\"crit\":[\"exp\"]", "\"zip\":\"DEF\"", "\"cty\":\"JWT\"")) {
            String forged = TokenForge.withHeader("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"k1\"," + extra + "}",
                    TokenForge.member(ID), SECRET_1);
            assertRejected(forged, ActorTokenFailure.HEADER);
        }
        assertRejected(TokenForge.withHeader("{\"alg\":\"HS256\",\"kid\":\"k1\"}", TokenForge.member(ID), SECRET_1),
                ActorTokenFailure.HEADER);
        assertRejected(TokenForge.withHeader("{\"alg\":\"HS256\",\"typ\":\"at+jwt\",\"kid\":\"k1\"}",
                TokenForge.member(ID), SECRET_1), ActorTokenFailure.HEADER);
    }

    @Test
    void malformedTokensAreRefused() {
        for (String bad : List.of("", "abc", "a.b", "a.b.c.d", "a.b.c.d.e", "....", "!!!.???.***",
                TokenForge.encode("not json") + "." + TokenForge.encode("{}") + ".sig",
                "x".repeat(5000))) {
            assertThatThrownBy(() -> verifier.verify(bad)).isInstanceOf(ActorTokenException.class);
        }
        assertRejected(null, ActorTokenFailure.MALFORMED);
    }

    @Test
    void nonObjectPayloadWithValidSignatureIsMalformed() {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}";
        assertRejected(TokenForge.withRawPayload(header, "[1,2,3]", SECRET_1), ActorTokenFailure.MALFORMED);
        assertRejected(TokenForge.withRawPayload(header, "not json", SECRET_1), ActorTokenFailure.MALFORMED);
    }

    @Test
    void verifierWithoutKeysRefusesEverything() {
        ActorTokenVerifier none = new ActorTokenVerifier(ActorTokenKeys.none(), Duration.ofSeconds(30),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(none.enabled()).isFalse();
        assertRejected(none, token(TokenForge.member(ID)), ActorTokenFailure.NOT_CONFIGURED);
    }

    // ---- key ids and rotation

    @Test
    void unknownKidIsRefused() {
        assertRejected(TokenForge.sign(TokenForge.member(ID), "k9", SECRET_1), ActorTokenFailure.UNKNOWN_KEY);
        assertRejected(TokenForge.sign(TokenForge.member(ID), "../../etc/passwd", SECRET_1),
                ActorTokenFailure.UNKNOWN_KEY);
    }

    @Test
    void tokenWithoutKidIsAcceptedOnlyWhileASingleKeyIsConfigured() {
        String noKid = TokenForge.sign(TokenForge.member(ID), null, SECRET_1);
        assertThat(verifier.verify(noKid)).isEqualTo(Actor.member(ID));
        assertRejected(verifier(SECRET_1, SECRET_2), noKid, ActorTokenFailure.UNKNOWN_KEY);
    }

    @Test
    void anyListedKeyVerifiesDuringRotation() {
        ActorTokenVerifier rotating = verifier(SECRET_1, SECRET_2);
        String fromNew = TokenForge.sign(TokenForge.member(ID), "k1", SECRET_1);
        String fromOld = TokenForge.sign(TokenForge.member(ID), "k2", SECRET_2);
        assertThat(rotating.verify(fromNew)).isEqualTo(Actor.member(ID));
        assertThat(rotating.verify(fromOld)).isEqualTo(Actor.member(ID));
        // after the old key is dropped its tokens stop working
        assertRejected(verifier(SECRET_1), fromOld, ActorTokenFailure.UNKNOWN_KEY);
        // a key id cannot be used to borrow another key's identity
        assertRejected(rotating, TokenForge.sign(TokenForge.member(ID), "k1", SECRET_2), ActorTokenFailure.SIGNATURE);
    }
}
