package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.identity.application.usecases.SessionService;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.support.IntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real sessions reaching real routes: tokens are checked in Postgres and resolve to an Actor. */
class SessionAuthIT extends IntegrationTest {

    @Autowired
    SessionService sessions;

    @Test
    void memberSessionReachesTheirOwnDataOnly() throws Exception {
        UUID ana = newMember();
        LoanSummary anasLoan = disburse(ana, 1_200, 8, 9, LocalDate.of(2026, 11, 10));
        LoanSummary bobsLoan = disburse(newMember(), 1_200, 8, 9, LocalDate.of(2026, 11, 10));
        String token = sessions.issueForMember(ana).token();

        mvc.perform(bearer(token, get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("MEMBER"))
                .andExpect(jsonPath("$.id").value(ana.toString()));
        mvc.perform(bearer(token, get("/api/v1/loans/{id}/schedule", anasLoan.loanId())))
                .andExpect(status().isOk());
        mvc.perform(bearer(token, get("/api/v1/loans/{id}/schedule", bobsLoan.loanId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void memberSessionOwnsTheSavingsGoalItCreates() throws Exception {
        UUID ana = newMember();
        mvc.perform(bearer(sessions.issueForMember(ana).token(), post("/api/v1/savings/goals"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberId":"%s","name":"Mixer","targetAmount":500,"monthlyAmount":50}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").value(ana.toString()));
    }

    @Test
    void memberSessionCannotUseDeskRoutes() throws Exception {
        String token = sessions.issueForMember(newMember()).token();
        mvc.perform(bearer(token, get("/api/v1/desk/loans/applications")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffRolesComeFromTheDatabaseAndChangeAtOnce() throws Exception {
        UUID officer = newStaff("{OFFICER}");
        String token = sessions.issueForStaff(officer).token();

        mvc.perform(bearer(token, get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("STAFF"))
                .andExpect(jsonPath("$.roles[0]").value("OFFICER"));
        mvc.perform(bearer(token, get("/api/v1/desk/loans/applications")))
                .andExpect(status().isOk());

        jdbc.sql("UPDATE identity.staff SET roles = '{}' WHERE id = :id").param("id", officer).update();
        mvc.perform(bearer(token, get("/api/v1/desk/loans/applications")))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendingSomeoneEndsTheirSessionsAtOnce() throws Exception {
        UUID member = newMember();
        UUID officer = newStaff("{OFFICER}");
        String memberToken = sessions.issueForMember(member).token();
        String staffToken = sessions.issueForStaff(officer).token();

        jdbc.sql("UPDATE identity.member SET status = 'SUSPENDED' WHERE id = :id").param("id", member).update();
        jdbc.sql("UPDATE identity.staff SET status = 'SUSPENDED' WHERE id = :id").param("id", officer).update();

        mvc.perform(bearer(memberToken, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
        mvc.perform(bearer(staffToken, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSession() throws Exception {
        String token = sessions.issueForMember(newMember()).token();

        mvc.perform(bearer(token, post("/api/v1/auth/logout"))).andExpect(status().isNoContent());

        mvc.perform(bearer(token, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
        mvc.perform(bearer(token, post("/api/v1/auth/logout"))).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutNeedsASignedInCaller() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAndIdleSessionsAreRejected() throws Exception {
        UUID expired = newMember();
        String expiredToken = sessions.issueForMember(expired).token();
        jdbc.sql("UPDATE identity.session SET expires_at = now() - interval '1 second' WHERE actor_id = :id")
                .param("id", expired).update();
        mvc.perform(bearer(expiredToken, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());

        UUID idle = newMember();
        String idleToken = sessions.issueForMember(idle).token();
        jdbc.sql("UPDATE identity.session SET last_seen_at = now() - interval '31 minutes' WHERE actor_id = :id")
                .param("id", idle).update();
        mvc.perform(bearer(idleToken, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());

        UUID idleOfficer = newStaff("{OFFICER}");
        String idleStaffToken = sessions.issueForStaff(idleOfficer).token();
        jdbc.sql("UPDATE identity.session SET last_seen_at = now() - interval '16 minutes' WHERE actor_id = :id")
                .param("id", idleOfficer).update();
        mvc.perform(bearer(idleStaffToken, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void activityKeepsASessionAliveAcrossTheIdleWindow() throws Exception {
        UUID member = newMember();
        String token = sessions.issueForMember(member).token();
        jdbc.sql("UPDATE identity.session SET last_seen_at = now() - interval '29 minutes' WHERE actor_id = :id")
                .param("id", member).update();

        mvc.perform(bearer(token, get("/api/v1/auth/me"))).andExpect(status().isOk());

        Integer idleMinutes = jdbc.sql("""
                SELECT EXTRACT(MINUTE FROM now() - last_seen_at)::int FROM identity.session WHERE actor_id = :id
                """).param("id", member).query(Integer.class).single();
        assertThat(idleMinutes).isZero();
    }

    @Test
    void tamperedAndUnknownTokensAreRejected() throws Exception {
        String token = sessions.issueForMember(newMember()).token();

        mvc.perform(bearer(token + "x", get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
        mvc.perform(bearer("seedses_not-a-real-token", get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
        mvc.perform(bearer("not-even-our-format", get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aBadTokenNeverFallsThroughToDevHeaders() throws Exception {
        mvc.perform(bearer("seedses_bad", get("/api/v1/auth/me"))
                        .header("X-Seed-Member-Id", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theInternalTokenStillWorks() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SERVICE"));
    }

    @Test
    void theDatabaseNeverHoldsAUsableToken() {
        UUID member = newMember();
        String token = sessions.issueForMember(member).token();

        String stored = jdbc.sql("SELECT token_hash FROM identity.session WHERE actor_id = :id")
                .param("id", member).query(String.class).single();
        assertThat(token).startsWith("seedses_");
        assertThat(stored).doesNotContain(token.substring("seedses_".length())).hasSize(64);
    }

    private UUID newMember() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.member (id, paypal_subject) VALUES (:id, :subject)")
                .param("id", id).param("subject", "paypal-" + id).update();
        return id;
    }

    private UUID newStaff(String roles) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.staff (id, email, display_name, roles) VALUES (:id, :email, 'Staff', :roles::text[])")
                .param("id", id).param("email", id + "@seed.test").param("roles", roles).update();
        return id;
    }

    private static MockHttpServletRequestBuilder bearer(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }
}
