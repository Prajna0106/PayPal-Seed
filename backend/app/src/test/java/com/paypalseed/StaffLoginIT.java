package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Staff get in with their email and password; a member or a guesser doesn't. */
class StaffLoginIT extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    ObjectMapper json;

    @Test
    void theRightPasswordOpensARealStaffSession() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");

        JsonNode session = logIn(email, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("OFFICER"))
                .andReturn().getResponse().getContentAsString().transform(this::read);

        mvc.perform(asUser(session, get("/api/v1/desk/loans/applications"))).andExpect(status().isOk());
    }

    @Test
    void emailsDontDependOnCapitals() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");

        logIn(email.toUpperCase(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void everyFailedLoginLooksTheSame() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");

        String wrongPassword = logIn(email, "not the password at all").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("staff-login-refused")).andReturn().getResponse().getContentAsString();
        String unknownEmail = logIn(newEmail(), PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("staff-login-refused")).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(wrongPassword).get("detail")).isEqualTo(json.readTree(unknownEmail).get("detail"));
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenAgainstTheRightOne() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");
        for (int i = 0; i < 5; i++) {
            logIn(email, "wrong password " + i).andExpect(status().isUnauthorized());
        }

        logIn(email, PASSWORD).andExpect(status().isUnauthorized());

        jdbc.sql("UPDATE identity.staff SET locked_until = now() - interval '1 second' WHERE email = :e").param("e", email).update();
        logIn(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aGoodLoginClearsEarlierFailures() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");
        for (int i = 0; i < 4; i++) {
            logIn(email, "wrong password " + i).andExpect(status().isUnauthorized());
        }
        logIn(email, PASSWORD).andExpect(status().isOk());

        logIn(email, "one more wrong password").andExpect(status().isUnauthorized());
        logIn(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aSuspendedPersonCantLogIn() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");
        jdbc.sql("UPDATE identity.staff SET active = false WHERE email = :e").param("e", email).update();

        logIn(email, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAHashIsStored() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");

        String stored = jdbc.sql("SELECT password_hash FROM identity.staff WHERE email = :e")
                .param("e", email).query(String.class).single();
        assertThat(stored).startsWith("$argon2id$").doesNotContain(PASSWORD);
    }

    @Test
    void aNewPasswordReplacesTheOldOne() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");

        saveAccount(email, "OFFICER", "a brand new password", null).andExpect(status().isOk());

        logIn(email, PASSWORD).andExpect(status().isUnauthorized());
        logIn(email, "a brand new password").andExpect(status().isOk());
    }

    @Test
    void aSuspendedPersonIsRefusedStraightAwayEvenWithAValidCredential() throws Exception {
        Staff staff = newStaffSession("OFFICER");
        mvc.perform(asUser(staff.session(), get("/api/v1/desk/staff"))).andExpect(status().isOk());

        jdbc.sql("UPDATE identity.staff SET active = false WHERE id = :id").param("id", staff.id()).update();

        mvc.perform(asUser(staff.session(), get("/api/v1/desk/staff"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aRoleTheyHaveLostStopsCountingStraightAway() throws Exception {
        Staff staff = newStaffSession("OFFICER");

        jdbc.sql("UPDATE identity.staff SET roles = ARRAY['CHECKER'] WHERE id = :id").param("id", staff.id()).update();

        mvc.perform(asUser(staff.session(), get("/api/v1/desk/staff"))).andExpect(status().isForbidden());
    }

    @Test
    void theDeskCanAskWhatSomeoneMayDoRightNow() throws Exception {
        Staff staff = newStaffSession("ADMIN");
        mvc.perform(asUser(staff.session(), get("/api/v1/auth/staff/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));

        jdbc.sql("UPDATE identity.staff SET roles = ARRAY['OFFICER'] WHERE id = :id").param("id", staff.id()).update();
        mvc.perform(asUser(staff.session(), get("/api/v1/auth/staff/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isEmpty());

        jdbc.sql("UPDATE identity.staff SET active = false WHERE id = :id").param("id", staff.id()).update();
        mvc.perform(asUser(staff.session(), get("/api/v1/auth/staff/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void metricsAreNotPublic() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
    }

    @Test
    void shortPasswordsAndUnknownRolesAreRefused() throws Exception {
        saveAccount(newEmail(), "OFFICER", "too short", null).andExpect(status().is4xxClientError());
        saveAccount(newEmail(), "SUPERUSER", PASSWORD, null).andExpect(status().is4xxClientError());
    }

    @Test
    void onlyTheServiceTokenReachesTheInternalRoutes() throws Exception {
        String email = newEmail();
        String body = """
                {"email":"%s","displayName":"Someone","roles":["ADMIN"],"password":"%s"}""".formatted(email, PASSWORD);
        mvc.perform(post("/internal/auth/staff/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/auth/staff/accounts").header("X-Seed-Member-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCreatesAColleagueWhoCanThenLogIn() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        String email = newEmail();

        createColleague(admin, email, "OFFICER", PASSWORD).andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffId").exists());

        logIn(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("OFFICER"));
    }

    @Test
    void onlyAnAdminCanCreateAColleague() throws Exception {
        for (String role : new String[] {"OFFICER", "CHECKER"}) {
            createColleague(newStaffSession(role), newEmail(), "ADMIN", PASSWORD).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/desk/staff").header("X-Seed-Member-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(colleagueBody(newEmail(), "ADMIN", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSuspendedAdminCantCreateAColleague() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        jdbc.sql("UPDATE identity.staff SET active = false WHERE id = :id").param("id", admin.id()).update();

        createColleague(admin, newEmail(), "ADMIN", PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void creatingAColleagueNeverTakesOverAnAccount() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        String email = newEmail();
        createStaff(email, "OFFICER");

        createColleague(admin, email.toUpperCase(), "ADMIN", "an attacker's new password").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("staff-email-taken"));

        logIn(email, "an attacker's new password").andExpect(status().isUnauthorized());
        logIn(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("OFFICER"));
    }

    @Test
    void aColleaguesPasswordMustBeLongEnough() throws Exception {
        createColleague(newStaffSession("ADMIN"), newEmail(), "OFFICER", "too short").andExpect(status().is4xxClientError());
    }

    @Test
    void staffCanChangeTheirOwnPassword() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");
        Staff staff = sessionOf(email);

        changePassword(staff, PASSWORD, "my very own new password").andExpect(status().isNoContent());

        logIn(email, PASSWORD).andExpect(status().isUnauthorized());
        logIn(email, "my very own new password").andExpect(status().isOk());
    }

    @Test
    void changingAPasswordNeedsTheCurrentOneAndCountsWrongGuesses() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");
        Staff staff = sessionOf(email);

        changePassword(staff, "not the current password", "my very own new password").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("current-password-wrong"));
        for (int i = 0; i < 4; i++) {
            changePassword(staff, "not the current password", "my very own new password").andExpect(status().isUnprocessableEntity());
        }

        logIn(email, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void aDemoPersonaHasNoPasswordToChange() throws Exception {
        mvc.perform(post("/api/v1/auth/staff/password").header("X-Seed-Staff-Id", UUID.randomUUID().toString())
                        .header("X-Seed-Staff-Roles", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"anything at all","newPassword":"my very own new password"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("staff-account-required"));
    }

    @Test
    void staffCantDecideTheirOwnLoanOnceTheirMemberAccountIsLinked() throws Exception {
        UUID member = UUID.fromString(read(mvc.perform(post("/internal/members/login").header("Authorization", SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"paypalPayerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("member").get("memberId").asText());
        String email = newEmail();
        saveAccount(email, "OFFICER", PASSWORD, member).andExpect(status().isOk());
        JsonNode session = read(logIn(email, PASSWORD).andReturn().getResponse().getContentAsString());
        UUID application = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO lending.loan_application (id, member_id, purpose, requested_amount_cents, term_months,
                    preferred_due_day, payout_wallet, unlocked_tier, max_monthly_instalment_cents,
                    monthly_instalment_cents, status)
                VALUES (:id, :member, 'Own loan', 50000, 6, 10, 'PAYPAL', 'UNQUALIFIED', 10000, 8334, 'SUBMITTED')
                """).param("id", application).param("member", member).update();

        for (String action : new String[] {"approve", "reject", "disburse"}) {
            mvc.perform(asUser(session, post("/api/v1/desk/loans/applications/{id}/" + action, application)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("sod-violation"));
        }
    }

    private record Staff(UUID id, JsonNode session) {
    }

    private Staff newStaffSession(String role) throws Exception {
        String email = newEmail();
        createStaff(email, role);
        JsonNode session = read(logIn(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Staff(UUID.fromString(session.get("staffId").asText()), session);
    }

    private Staff sessionOf(String email) throws Exception {
        JsonNode session = read(logIn(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Staff(UUID.fromString(session.get("staffId").asText()), session);
    }

    private ResultActions createColleague(Staff admin, String email, String role, String password) throws Exception {
        return mvc.perform(asUser(admin.session(), post("/api/v1/desk/staff")).contentType(MediaType.APPLICATION_JSON)
                .content(colleagueBody(email, role, password)));
    }

    private static String colleagueBody(String email, String role, String password) {
        return """
                {"email":"%s","displayName":"A Colleague","roles":["%s"],"password":"%s"}""".formatted(email, role, password);
    }

    private ResultActions changePassword(Staff staff, String current, String next) throws Exception {
        return mvc.perform(asUser(staff.session(), post("/api/v1/auth/staff/password")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"%s","newPassword":"%s"}""".formatted(current, next)));
    }

    private void createStaff(String email, String role) throws Exception {
        saveAccount(email, role, PASSWORD, null).andExpect(status().isOk());
    }

    private ResultActions saveAccount(String email, String role, String password, UUID memberId) throws Exception {
        String member = memberId == null ? "" : ",\"memberId\":\"" + memberId + "\"";
        return mvc.perform(post("/internal/auth/staff/accounts").header("Authorization", SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","displayName":"Staff Person","roles":["%s"],"password":"%s"%s}"""
                        .formatted(email, role, password, member)));
    }

    private ResultActions logIn(String email, String password) throws Exception {
        return mvc.perform(post("/internal/auth/staff/password/login").header("Authorization", SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password)));
    }

    private static MockHttpServletRequestBuilder asUser(JsonNode session, MockHttpServletRequestBuilder request) {
        StringBuilder roles = new StringBuilder();
        session.get("roles").forEach(role -> roles.append(role.asText()).append(','));
        return request.header("X-Seed-Staff-Id", session.get("staffId").asText()).header("X-Seed-Staff-Roles", roles.toString());
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newEmail() {
        return UUID.randomUUID() + "@seed.test";
    }
}
