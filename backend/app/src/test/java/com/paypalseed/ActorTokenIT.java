package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.ActorAuthentication;
import com.paypalseed.platform.security.ActorTokenConfiguration;
import com.paypalseed.platform.security.ActorTokenIssuer;
import com.paypalseed.support.IntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR 0016: signed actor tokens on /api/**, the internal service token on /internal/**, and the
 * production start-up guard. The test profile keeps dev headers on, so these tests also show that
 * a token wins over them.
 */
@TestPropertySource(properties = "seed.security.actor-token.secrets=k1:"
        + "it-secret-0123456789-0123456789-0123456789-0123456789-0123456789")
class ActorTokenIT extends IntegrationTest {

    private static final String SECRET = "it-secret-0123456789-0123456789-0123456789-0123456789-0123456789";

    private final ActorTokenIssuer issuer = new ActorTokenIssuer("k1", SECRET, Clock.systemUTC());
    private LoanSummary loan;

    @BeforeEach
    void loan() {
        loan = disburse(UUID.randomUUID(), 1_200, 8, 9, LocalDate.of(2026, 11, 10));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void memberTokenReadsOwnLoans() throws Exception {
        mvc.perform(get("/api/v1/loans").header("Authorization", bearer(issuer.member(loan.memberId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].loanId").value(loan.loanId().toString()));
    }

    @Test
    void memberTokenOfSomeoneElseSeesNothingOfOthers() throws Exception {
        mvc.perform(get("/api/v1/loans").header("Authorization", bearer(issuer.member(UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/loans/{id}/schedule", loan.loanId())
                        .header("Authorization", bearer(issuer.member(UUID.randomUUID()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void staffTokenReadsTheDeskQueue() throws Exception {
        for (Set<Actor.Role> roles : List.of(Set.of(Actor.Role.OFFICER), Set.of(Actor.Role.CHECKER),
                Set.of(Actor.Role.ADMIN))) {
            mvc.perform(get("/api/v1/desk/hardship/cases")
                            .header("Authorization", bearer(issuer.staff(UUID.randomUUID(), roles))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void memberTokenOnADeskEndpointIs403() throws Exception {
        mvc.perform(get("/api/v1/desk/hardship/cases")
                        .header("Authorization", bearer(issuer.member(loan.memberId()))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(get("/api/v1/desk/agents/kill-switch")
                        .header("Authorization", bearer(issuer.member(loan.memberId()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffTokenWithoutRolesOnADeskEndpointIs403() throws Exception {
        mvc.perform(get("/api/v1/desk/hardship/cases")
                        .header("Authorization", bearer(issuer.staff(UUID.randomUUID(), Set.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffTokenIsNotAMember() throws Exception {
        mvc.perform(get("/api/v1/loans")
                        .header("Authorization", bearer(issuer.staff(UUID.randomUUID(), Set.of(Actor.Role.ADMIN)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenIs401WithABareChallenge() throws Exception {
        mvc.perform(get("/api/v1/loans"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void everyKindOfBadTokenGetsTheSameAnswer() throws Exception {
        String expired = new ActorTokenIssuer("k1", SECRET, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-10)))
                .member(loan.memberId());
        String notYetValid = new ActorTokenIssuer("k1", SECRET, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(10)))
                .member(loan.memberId());
        String body = mvc.perform(get("/api/v1/loans")).andReturn().getResponse().getContentAsString();
        String foreignKey = new ActorTokenIssuer("k1", "another-secret-0123456789-0123456789-0123456789-abcd",
                Clock.systemUTC()).member(loan.memberId());
        String unknownKid = new ActorTokenIssuer("k7", SECRET, Clock.systemUTC()).member(loan.memberId());
        String tampered = issuer.member(loan.memberId());
        tampered = tampered.substring(0, tampered.length() - 3) + "AAA";
        for (String token : new String[] {"garbage", "a.b.c", expired, notYetValid, foreignKey, unknownKid, tampered}) {
            mvc.perform(get("/api/v1/loans").header("Authorization", bearer(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(content().string(body));
        }
    }

    @Test
    void internalServiceTokenCannotActOnApi() throws Exception {
        mvc.perform(get("/api/v1/loans").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/desk/hardship/cases").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isUnauthorized());
        // dev headers do not rescue it: a bearer credential that is wrong for the path is final
        mvc.perform(get("/api/v1/loans").header("Authorization", SERVICE_TOKEN)
                        .header("X-Seed-Member-Id", loan.memberId().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actorTokensCannotOpenInternalRoutes() throws Exception {
        mvc.perform(post("/internal/jobs/hardship/sweep").header("Authorization", bearer(issuer.member(loan.memberId()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/jobs/hardship/sweep")
                        .header("Authorization", bearer(issuer.staff(UUID.randomUUID(), Set.of(Actor.Role.ADMIN)))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/jobs/hardship/sweep").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void tokenWinsOverDevHeaders() throws Exception {
        UUID headerMember = UUID.randomUUID();
        mvc.perform(get("/api/v1/loans").header("X-Seed-Member-Id", headerMember.toString())
                        .header("Authorization", bearer(issuer.member(loan.memberId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].loanId").value(loan.loanId().toString()));
        // and a bad token is not replaced by the header
        mvc.perform(get("/api/v1/loans").header("X-Seed-Member-Id", loan.memberId().toString())
                        .header("Authorization", bearer("garbage")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void devHeadersStillWorkWithoutAToken() throws Exception {
        mvc.perform(get("/api/v1/loans").header("X-Seed-Member-Id", loan.memberId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].loanId").value(loan.loanId().toString()));
    }

    // ---- start-up guard (the real configuration classes, a production profile)

    private final ApplicationContextRunner production = new ApplicationContextRunner()
            .withUserConfiguration(ActorTokenConfiguration.class, ActorAuthentication.class)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"));

    @Test
    void productionDoesNotStartWithoutASecret() {
        production.run(context -> assertThat(context.getStartupFailure())
                .rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed.security.actor-token.secrets"));
    }

    @Test
    void productionDoesNotStartWithDevHeaderAuthOn() {
        production.withPropertyValues("seed.security.actor-token.secrets=k1:" + SECRET,
                        "seed.security.dev-header-auth=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause().isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("dev-header-auth"));
    }

    @Test
    void productionStartsWithASecretAndNoDevHeaders() {
        production.withPropertyValues("seed.security.actor-token.secrets=k1:" + SECRET,
                        "seed.security.internal-token=prod-internal-token-0123456789")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ActorAuthentication.class));
    }

    @Test
    void productionRejectsAShortSecret() {
        production.withPropertyValues("seed.security.actor-token.secrets=k1:too-short")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause().hasMessageContaining("at least 32").hasMessageNotContaining("too-short"));
    }
}
