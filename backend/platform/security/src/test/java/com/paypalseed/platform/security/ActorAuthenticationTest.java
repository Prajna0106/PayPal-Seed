package com.paypalseed.platform.security;

import static com.paypalseed.platform.security.TokenForge.NOW;
import static com.paypalseed.platform.security.TokenForge.SECRET_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ActorAuthenticationTest {

    private static final String INTERNAL = "internal-token-0123456789";
    private static final UUID ID = UUID.fromString("0b0e7a52-6f0a-4b55-a1a4-3c1d1f5c9a01");
    private static final UUID OTHER = UUID.fromString("7c2d3e44-1111-4222-8333-444455556666");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ActorTokenKeys keys = ActorTokenKeys.parse(List.of("k1:" + SECRET_1));
    private final ActorTokenVerifier verifier = new ActorTokenVerifier(keys, Duration.ofSeconds(30), clock);
    private final ActorTokenIssuer issuer = ActorTokenIssuer.signingWith(keys, clock);

    private ActorAuthentication auth(boolean devHeaders) {
        return new ActorAuthentication(INTERNAL, devHeaders, verifier);
    }

    private static MockHttpServletRequest request(String path, String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        return request;
    }

    @Test
    void actorTokenOpensApiAsTheNamedActor() {
        assertThat(auth(false).authenticate(request("/api/v1/loans", issuer.member(ID)))).contains(Actor.member(ID));
        Optional<Actor> staff = auth(false)
                .authenticate(request("/api/v1/desk/hardship/cases", issuer.staff(ID, Set.of(Actor.Role.OFFICER))));
        assertThat(staff).hasValueSatisfying(a -> {
            assertThat(a.type()).isEqualTo(Actor.Type.STAFF);
            assertThat(a.roles()).containsExactly(Actor.Role.OFFICER);
        });
    }

    @Test
    void serviceTokenOpensInternalOnly() {
        assertThat(auth(false).authenticate(request("/internal/jobs/cob/catch-up", INTERNAL)))
                .hasValueSatisfying(a -> assertThat(a.roles()).containsExactly(Actor.Role.SERVICE));
        assertThat(auth(false).authenticate(request("/api/v1/loans", INTERNAL))).isEmpty();
        assertThat(auth(false).authenticate(request("/api/v1/desk/hardship/cases", INTERNAL))).isEmpty();
        assertThat(auth(true).authenticate(request("/api/v1/loans", INTERNAL))).as("dev too").isEmpty();
    }

    @Test
    void actorTokenNeverOpensInternalOrOtherPaths() {
        String member = issuer.member(ID);
        String staff = issuer.staff(ID, Set.of(Actor.Role.ADMIN));
        for (String path : List.of("/internal/jobs/cob/catch-up", "/internal/demo-clock/advance", "/agent/v1/proposals",
                "/actuator/health", "/")) {
            assertThat(auth(false).authenticate(request(path, member))).as(path).isEmpty();
            assertThat(auth(false).authenticate(request(path, staff))).as(path).isEmpty();
        }
    }

    @Test
    void oddSpellingsOfThePathFailClosed() {
        String member = issuer.member(ID);
        assertThat(auth(false).authenticate(request("/api/../internal/jobs/x", member))).isEmpty();
        assertThat(auth(false).authenticate(request("//internal/jobs/x", member))).isEmpty();
        assertThat(auth(false).authenticate(request("/internal;a=b/jobs/x", member))).isEmpty();
        assertThat(auth(false).authenticate(request("/%69nternal/jobs/x", INTERNAL))).isEmpty();
        assertThat(auth(false).authenticate(request("/api/../internal/jobs/x", INTERNAL))).isPresent();
        assertThat(auth(false).authenticate(request("//api//v1///loans", member))).contains(Actor.member(ID));
    }

    @Test
    void badOrMissingTokensGiveNoActor() {
        assertThat(auth(false).authenticate(request("/api/v1/loans", null))).isEmpty();
        assertThat(auth(false).authenticate(request("/api/v1/loans", ""))).isEmpty();
        assertThat(auth(false).authenticate(request("/api/v1/loans", "garbage"))).isEmpty();
        assertThat(auth(false).authenticate(request("/api/v1/loans",
                TokenForge.sign(TokenForge.member(ID), "k1", TokenForge.SECRET_2)))).isEmpty();
        assertThat(auth(false).authenticate(request("/api/v1/loans", TokenForge.unsigned(TokenForge.member(ID))))).isEmpty();
    }

    @Test
    void devHeadersAreIgnoredUnlessEnabled() {
        MockHttpServletRequest request = request("/api/v1/loans", null);
        request.addHeader("X-Seed-Member-Id", ID.toString());
        assertThat(auth(false).authenticate(request)).isEmpty();
        assertThat(auth(true).authenticate(request)).contains(Actor.member(ID));
    }

    @Test
    void inDevTheTokenWinsOverHeaders() {
        MockHttpServletRequest request = request("/api/v1/loans", issuer.member(OTHER));
        request.addHeader("X-Seed-Member-Id", ID.toString());
        assertThat(auth(true).authenticate(request)).contains(Actor.member(OTHER));
    }

    @Test
    void inDevABadTokenIsNotRescuedByHeaders() {
        MockHttpServletRequest request = request("/api/v1/loans", "forged.token.value");
        request.addHeader("X-Seed-Member-Id", ID.toString());
        assertThat(auth(true).authenticate(request)).isEmpty();
    }

    @Test
    void devStaffHeadersKeepTheirOldMeaning() {
        MockHttpServletRequest request = request("/api/v1/desk/hardship/cases", null);
        request.addHeader("X-Seed-Staff-Id", ID.toString());
        request.addHeader("X-Seed-Staff-Roles", "officer, service, nonsense");
        assertThat(auth(true).authenticate(request)).hasValueSatisfying(a -> {
            assertThat(a.type()).isEqualTo(Actor.Type.STAFF);
            assertThat(a.roles()).containsExactly(Actor.Role.OFFICER);
        });
    }

    @Test
    void withoutASecretNoTokenIsAccepted() {
        ActorTokenVerifier none = new ActorTokenVerifier(ActorTokenKeys.none(), Duration.ofSeconds(30), clock);
        ActorAuthentication noKeys = new ActorAuthentication(INTERNAL, true, none);
        assertThat(noKeys.authenticate(request("/api/v1/loans", issuer.member(ID)))).isEmpty();
        assertThat(noKeys.authenticate(request("/internal/jobs/x", INTERNAL))).isPresent();
    }

    @Test
    void shortInternalTokenIsNeverAccepted() {
        ActorAuthentication weak = new ActorAuthentication("short", false, verifier);
        assertThat(weak.authenticate(request("/internal/jobs/x", "short"))).isEmpty();
        assertThat(weak.authenticate(request("/internal/jobs/x", ""))).isEmpty();
    }
}
