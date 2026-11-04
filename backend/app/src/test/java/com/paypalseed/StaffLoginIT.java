package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.identity.application.usecases.StaffLoginService;
import com.paypalseed.identity.infrastructure.persistence.StaffCredentialRepository;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.ActorTokenIssuer;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Staff get in with their email and password; a member, the member app or a guesser doesn't (ADR 0023). */
@TestPropertySource(properties = {
        "seed.security.actor-token.secrets=m1:" + StaffLoginIT.MEMBER_SECRET,
        "seed.security.staff-actor-token.secrets=s1:" + StaffLoginIT.STAFF_SECRET})
class StaffLoginIT extends IntegrationTest {

    static final String MEMBER_SECRET = "member-app-secret-0123456789-0123456789-0123456789";
    static final String STAFF_SECRET = "desk-staff-secret-0123456789-0123456789-0123456789";
    private static final String PASSWORD = "correct horse battery";
    private static final String HOME = "10.0.0.1";

    private final ActorTokenIssuer desk = new ActorTokenIssuer("s1", STAFF_SECRET, Clock.systemUTC());
    private final ActorTokenIssuer memberApp = new ActorTokenIssuer("m1", MEMBER_SECRET, Clock.systemUTC());

    @Autowired
    ObjectMapper json;
    @Autowired
    StaffLoginService logins;
    @Autowired
    UnderwritingFacade underwriting;
    @Autowired
    FairLoanCheckService fairChecks;
    @Autowired
    StaffCredentialRepository failureCounter;

    // ---- logging in

    @Test
    void theRightPasswordOpensARealStaffSession() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");

        JsonNode session = read(logIn(email, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("OFFICER"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn().getResponse().getContentAsString());

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

    // ---- lockouts

    @Test
    void fiveWrongPasswordsFromOneAddressLockThatAddressEvenAgainstTheRightOne() throws Exception {
        String email = newEmail();
        UUID id = createStaff(email, "OFFICER");
        for (int i = 0; i < 5; i++) {
            logIn(email, "wrong password " + i, HOME).andExpect(status().isUnauthorized());
        }

        logIn(email, PASSWORD, HOME).andExpect(status().isUnauthorized());

        jdbc.sql("UPDATE identity.staff_login_failure SET locked_until = now() - interval '1 second' WHERE staff_id = :id")
                .param("id", id).update();
        logIn(email, PASSWORD, HOME).andExpect(status().isOk());
    }

    @Test
    void aGuesserLocksOnlyTheirOwnAddressSoTheyCantKeepAColleagueOut() throws Exception {
        String email = newEmail();
        createStaff(email, "ADMIN");
        for (int i = 0; i < 6; i++) {
            logIn(email, "an attacker's guess " + i, "203.0.113.9").andExpect(status().isUnauthorized());
        }

        logIn(email, PASSWORD, "203.0.113.9").andExpect(status().isUnauthorized());
        logIn(email, PASSWORD, HOME).andExpect(status().isOk());
    }

    @Test
    void guessesSpreadOverManyAddressesLockTheWholeAccount() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");
        for (int i = 0; i < 50; i++) {
            logIn(email, "guess " + i, "198.51.100." + i).andExpect(status().isUnauthorized());
        }

        logIn(email, PASSWORD, HOME).andExpect(status().isUnauthorized());
    }

    @Test
    void failuresOutsideTheWindowNoLongerCount() throws Exception {
        String email = newEmail();
        UUID id = createStaff(email, "OFFICER");
        for (int i = 0; i < 4; i++) {
            logIn(email, "an old typo " + i, HOME).andExpect(status().isUnauthorized());
        }
        jdbc.sql("UPDATE identity.staff_login_failure SET window_started_at = now() - interval '16 minutes' WHERE staff_id = :id")
                .param("id", id).update();

        logIn(email, "one more typo", HOME).andExpect(status().isUnauthorized());

        assertThat(failures(id, HOME)).isEqualTo(1);
        logIn(email, PASSWORD, HOME).andExpect(status().isOk());
    }

    @Test
    void aGoodLoginClearsEarlierFailuresFromThatAddress() throws Exception {
        String email = newEmail();
        UUID id = createStaff(email, "OFFICER");
        for (int i = 0; i < 4; i++) {
            logIn(email, "wrong password " + i, HOME).andExpect(status().isUnauthorized());
        }
        logIn(email, PASSWORD, HOME).andExpect(status().isOk());

        assertThat(failures(id, HOME)).isZero();
        logIn(email, "one more wrong password", HOME).andExpect(status().isUnauthorized());
        logIn(email, PASSWORD, HOME).andExpect(status().isOk());
    }

    @Test
    void parallelFailuresAreEachCountedOnceSoNoneSlipsPastTheLimit() throws Exception {
        String email = newEmail();
        UUID id = createStaff(email, "OFFICER");
        int guesses = 20;
        Instant now = Instant.now();
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < guesses; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    failureCounter.recordFailure(id, HOME, 5, now.minus(Duration.ofMinutes(15)), now.plus(Duration.ofMinutes(15)), now);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures(id, HOME)).isEqualTo(guesses);
        assertThatThrownBy(() -> logins.logInWithPassword(email, PASSWORD, HOME)).isInstanceOf(DomainException.class);
    }

    // ---- who may create and change accounts

    @Test
    void onlyTheStaffServiceTokenReachesTheStaffRoutes() throws Exception {
        String email = newEmail();
        String account = """
                {"email":"%s","displayName":"Someone","roles":["ADMIN"],"password":"%s"}""".formatted(email, PASSWORD);
        String login = """
                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD);

        for (String path : List.of("/internal/auth/staff/accounts", "/internal/auth/staff/password/login")) {
            String body = path.endsWith("accounts") ? account : login;
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
            // the member app holds this token
            mvc.perform(post(path).header("Authorization", SERVICE_TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).header("X-Seed-Member-Id", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM identity.staff WHERE email = :e").param("e", email).query(Integer.class).single())
                .isZero();
    }

    @Test
    void theStaffServiceTokenOpensNothingElse() throws Exception {
        mvc.perform(post("/internal/jobs/hardship/sweep").header("Authorization", STAFF_SERVICE_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/members/login").header("Authorization", STAFF_SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paypalPayerId\":\"P-1\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/desk/staff").header("Authorization", STAFF_SERVICE_TOKEN)).andExpect(status().isUnauthorized());
    }

    @Test
    void theMemberAppCantSignAStaffToken() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        String forged = memberApp.staff(admin.id(), Set.of(Actor.Role.ADMIN), Duration.ofSeconds(60), Instant.now());

        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + signed(admin, Instant.now())))
                .andExpect(status().isOk());
    }

    @Test
    void theScriptOnlyResetsThePasswordOfAnExistingAccount() throws Exception {
        UUID member = newMember();
        String email = newEmail();
        UUID id = UUID.fromString(read(saveAccount(email, "OFFICER", PASSWORD, member).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true)).andReturn().getResponse().getContentAsString())
                .get("staffId").asText());

        saveAccount(email, "ADMIN", "a brand new password", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false)).andExpect(jsonPath("$.staffId").value(id.toString()));

        logIn(email, PASSWORD).andExpect(status().isUnauthorized());
        logIn(email, "a brand new password").andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("OFFICER"));
        assertThat(jdbc.sql("SELECT linked_member_id FROM identity.staff WHERE id = :id").param("id", id).query(UUID.class).single())
                .isEqualTo(member);
    }

    @Test
    void shortPasswordsAndUnknownRolesAreRefused() throws Exception {
        saveAccount(newEmail(), "OFFICER", "too short", null).andExpect(status().is4xxClientError());
        saveAccount(newEmail(), "SUPERUSER", PASSWORD, null).andExpect(status().is4xxClientError());
    }

    @Test
    void anAdminCreatesAColleagueWhoMustChooseTheirOwnPasswordFirst() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        String email = newEmail();

        createColleague(admin, email, "OFFICER", PASSWORD, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffId").exists());

        JsonNode session = read(logIn(email, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("OFFICER"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andReturn().getResponse().getContentAsString());
        Staff colleague = new Staff(UUID.fromString(session.get("staffId").asText()), session);

        mvc.perform(asUser(session, get("/api/v1/desk/loans/applications"))).andExpect(status().isForbidden());
        mvc.perform(asUser(session, get("/api/v1/auth/staff/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isEmpty()).andExpect(jsonPath("$.passwordChangeRequired").value(true));

        changePassword(colleague, PASSWORD, "my very own new password").andExpect(status().isNoContent());

        mvc.perform(asUser(session, get("/api/v1/desk/loans/applications"))).andExpect(status().isOk());
        logIn(email, "my very own new password").andExpect(jsonPath("$.passwordChangeRequired").value(false));
    }

    @Test
    void onlyAnAdminCanCreateAColleague() throws Exception {
        for (String role : new String[] {"OFFICER", "CHECKER"}) {
            createColleague(newStaffSession(role), newEmail(), "ADMIN", PASSWORD, null).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/desk/staff").header("X-Seed-Member-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(colleagueBody(newEmail(), "ADMIN", PASSWORD, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSuspendedAdminCantCreateAColleague() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        jdbc.sql("UPDATE identity.staff SET active = false WHERE id = :id").param("id", admin.id()).update();

        createColleague(admin, newEmail(), "ADMIN", PASSWORD, null).andExpect(status().isUnauthorized());
    }

    @Test
    void creatingAColleagueNeverTakesOverAnAccount() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        String email = newEmail();
        createStaff(email, "OFFICER");

        createColleague(admin, email.toUpperCase(), "ADMIN", "an attacker's new password", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("staff-email-taken"));

        logIn(email, "an attacker's new password").andExpect(status().isUnauthorized());
        logIn(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("OFFICER"));
    }

    @Test
    void aColleaguesPasswordMustBeLongEnough() throws Exception {
        createColleague(newStaffSession("ADMIN"), newEmail(), "OFFICER", "too short", null).andExpect(status().is4xxClientError());
    }

    @Test
    void aMemberAccountLinksToOneStaffLoginOnly() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        UUID member = newMember();

        createColleague(admin, newEmail(), "OFFICER", PASSWORD, member).andExpect(status().isCreated());
        createColleague(admin, newEmail(), "OFFICER", PASSWORD, member).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("member-already-linked"));
        createColleague(admin, newEmail(), "OFFICER", PASSWORD, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void anAdminChangesRolesSuspendsAndLinksAndEveryChangeIsAudited() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        Staff officer = newStaffSession("OFFICER");
        UUID member = newMember();

        changeAccess(admin, officer.id(), "{\"roles\":[\"CHECKER\"],\"linkMemberId\":\"" + member + "\"}").andExpect(status().isNoContent());
        mvc.perform(asUser(admin.session(), get("/api/v1/desk/staff"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.staffId == '" + officer.id() + "')].roles[0]").value("CHECKER"))
                .andExpect(jsonPath("$[?(@.staffId == '" + officer.id() + "')].linkedMemberId").value(member.toString()))
                .andExpect(jsonPath("$[?(@.staffId == '" + officer.id() + "')].canLogIn").value(true));

        changeAccess(admin, officer.id(), "{\"active\":false}").andExpect(status().isNoContent());
        logIn(officer.email(), PASSWORD).andExpect(status().isUnauthorized());

        changeAccess(admin, officer.id(), "{\"active\":true,\"unlinkMember\":true}").andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT linked_member_id IS NULL FROM identity.staff WHERE id = :id").param("id", officer.id())
                .query(Boolean.class).single()).isTrue();
        assertThat(auditEntries(officer.id(), "STAFF_ACCESS_CHANGED")).isEqualTo(3);
        assertThat(auditEntries(officer.id(), "STAFF_ACCOUNT_CREATED")).isEqualTo(1);
        String payload = jdbc.sql("SELECT payload::text FROM governance.audit_entry WHERE subject_id = :id ORDER BY seq LIMIT 1")
                .param("id", officer.id()).query(String.class).single();
        assertThat(payload).doesNotContain(officer.email()).doesNotContain("Staff Person");
    }

    @Test
    void nobodyCanTakeAwayTheirOwnAdminRoleOrSuspendThemselves() throws Exception {
        Staff admin = newStaffSession("ADMIN");

        changeAccess(admin, admin.id(), "{\"roles\":[\"OFFICER\"]}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("staff-self-lockout"));
        changeAccess(admin, admin.id(), "{\"active\":false}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("staff-self-lockout"));
        logIn(admin.email(), PASSWORD).andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void onlyAnAdminChangesAccessOrResetsPasswords() throws Exception {
        Staff officer = newStaffSession("OFFICER");
        Staff target = newStaffSession("OFFICER");

        changeAccess(officer, target.id(), "{\"roles\":[\"ADMIN\"]}").andExpect(status().isForbidden());
        mvc.perform(asUser(officer.session(), post("/api/v1/desk/staff/{id}/password", target.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"a starting password\"}"))
                .andExpect(status().isForbidden());
        changeAccess(newStaffSession("ADMIN"), UUID.randomUUID(), "{\"active\":false}").andExpect(status().isNotFound());
    }

    @Test
    void anAdminResetsAPasswordWhichEndsTheirSessionsAndMustBeChanged() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        Staff officer = newStaffSession("OFFICER");
        String before = signed(officer, Instant.now().minusSeconds(5));
        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + before)).andExpect(status().isOk());

        mvc.perform(asUser(admin.session(), post("/api/v1/desk/staff/{id}/password", officer.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"a starting password\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + before)).andExpect(status().isUnauthorized());
        logIn(officer.email(), PASSWORD).andExpect(status().isUnauthorized());
        logIn(officer.email(), "a starting password").andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
        assertThat(auditEntries(officer.id(), "STAFF_PASSWORD_RESET")).isEqualTo(1);
    }

    // ---- sessions

    @Test
    void loggingOutEndsEverySessionEvenACopiedCookie() throws Exception {
        Staff officer = newStaffSession("OFFICER");
        String copied = signed(officer, Instant.now().minusSeconds(30));
        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + copied)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/staff/logout").header("Authorization", "Bearer " + copied)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + copied)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + signed(officer, Instant.now())))
                .andExpect(status().isOk());
    }

    @Test
    void changingAPasswordEndsOtherSessions() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");
        Staff staff = sessionOf(email);
        String other = signed(staff, Instant.now().minusSeconds(30));

        changePassword(staff, PASSWORD, "my very own new password").andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + other)).andExpect(status().isUnauthorized());
        logIn(email, PASSWORD).andExpect(status().isUnauthorized());
        logIn(email, "my very own new password").andExpect(status().isOk());
        assertThat(auditEntries(staff.id(), "STAFF_PASSWORD_CHANGED")).isEqualTo(1);
    }

    @Test
    void aSignedSessionWithNoStartTimeEndsOnceSessionsWereEnded() throws Exception {
        Staff officer = newStaffSession("OFFICER");
        String noStart = desk.staff(officer.id(), Set.of(Actor.Role.OFFICER));
        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + noStart)).andExpect(status().isOk());

        logins.endSessions(officer.id());

        mvc.perform(get("/api/v1/desk/staff").header("Authorization", "Bearer " + noStart)).andExpect(status().isUnauthorized());
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

    // ---- own password

    @Test
    void staffCanChangeTheirOwnPassword() throws Exception {
        String email = newEmail();
        createStaff(email, "OFFICER");
        Staff staff = sessionOf(email);

        changePassword(staff, PASSWORD, "my very own new password").andExpect(status().isNoContent());
        changePassword(staff, "my very own new password", "my very own new password").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("password-unchanged"));

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

        // locked after five wrong guesses, even with the right current password
        changePassword(staff, PASSWORD, "my very own new password").andExpect(status().isUnprocessableEntity());
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

    // ---- own cases

    @Test
    void staffCantDecideTheirOwnLoanOnceTheirMemberAccountIsLinked() throws Exception {
        UUID member = newMember();
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

    @Test
    void staffCantDecideTheirOwnFairCheckOrAskTheFundToCoverTheirOwnLoan() throws Exception {
        Staff admin = newStaffSession("ADMIN");
        UUID member = newMember();
        String email = newEmail();
        createColleague(admin, email, "OFFICER", PASSWORD, member).andExpect(status().isCreated());
        JsonNode first = read(logIn(email, PASSWORD).andReturn().getResponse().getContentAsString());
        changePassword(new Staff(UUID.fromString(first.get("staffId").asText()), first, email), PASSWORD, "my very own new password")
                .andExpect(status().isNoContent());
        JsonNode session = read(logIn(email, "my very own new password").andReturn().getResponse().getContentAsString());

        underwriting.grantConsent(member);
        FairCheckCardView card = fairChecks.runFairLoanCheck(member, new RunFairCheckCommand(null, "Own stand mixer", 120_000L, 8,
                List.of(new IncomeMonthDto("2026-05", 165_000L), new IncomeMonthDto("2026-06", 172_000L),
                        new IncomeMonthDto("2026-07", 118_000L), new IncomeMonthDto("2026-08", 165_000L),
                        new IncomeMonthDto("2026-09", 170_000L), new IncomeMonthDto("2026-10", 170_000L)),
                160_000L, 90_000L, 0, false));

        mvc.perform(asUser(session, post("/api/v1/desk/underwriting/assessments/{id}/decision", card.assessmentId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\",\"reason\":\"Looks fine\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("sod-violation"));
        mvc.perform(asUser(session, post("/api/v1/desk/reserve/cover-requests")).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + member + "\",\"loanId\":\"" + UUID.randomUUID()
                                + "\",\"amountCents\":10000,\"reasonCode\":\"HARDSHIP\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("sod-violation"));
    }

    // ---- helpers

    private record Staff(UUID id, JsonNode session, String email) {
        Staff(UUID id, JsonNode session) {
            this(id, session, null);
        }
    }

    private Staff newStaffSession(String role) throws Exception {
        String email = newEmail();
        createStaff(email, role);
        return sessionOf(email);
    }

    private Staff sessionOf(String email) throws Exception {
        JsonNode session = read(logIn(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Staff(UUID.fromString(session.get("staffId").asText()), session, email);
    }

    private String signed(Staff staff, Instant sessionStart) {
        Set<Actor.Role> roles = EnumSet.noneOf(Actor.Role.class);
        staff.session().get("roles").forEach(role -> roles.add(Actor.Role.valueOf(role.asText())));
        return desk.staff(staff.id(), roles, Duration.ofSeconds(60), sessionStart);
    }

    private JsonNode withRoles(JsonNode session, String... roles) {
        var copy = session.deepCopy();
        var array = ((com.fasterxml.jackson.databind.node.ObjectNode) copy).putArray("roles");
        for (String role : roles) {
            array.add(role);
        }
        return copy;
    }

    private UUID newMember() throws Exception {
        return UUID.fromString(read(mvc.perform(post("/internal/members/login").header("Authorization", SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"paypalPayerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("member").get("memberId").asText());
    }

    private ResultActions createColleague(Staff admin, String email, String role, String password, UUID memberId) throws Exception {
        return mvc.perform(asUser(admin.session(), post("/api/v1/desk/staff")).contentType(MediaType.APPLICATION_JSON)
                .content(colleagueBody(email, role, password, memberId)));
    }

    private static String colleagueBody(String email, String role, String password, UUID memberId) {
        String member = memberId == null ? "" : ",\"memberId\":\"" + memberId + "\"";
        return """
                {"email":"%s","displayName":"A Colleague","roles":["%s"],"password":"%s"%s}""".formatted(email, role, password, member);
    }

    private ResultActions changeAccess(Staff admin, UUID staffId, String body) throws Exception {
        return mvc.perform(asUser(admin.session(), patch("/api/v1/desk/staff/{id}", staffId)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions changePassword(Staff staff, String current, String next) throws Exception {
        return mvc.perform(asUser(staff.session(), post("/api/v1/auth/staff/password")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"%s","newPassword":"%s"}""".formatted(current, next)));
    }

    private UUID createStaff(String email, String role) throws Exception {
        return UUID.fromString(read(saveAccount(email, role, PASSWORD, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("staffId").asText());
    }

    private ResultActions saveAccount(String email, String role, String password, UUID memberId) throws Exception {
        String member = memberId == null ? "" : ",\"memberId\":\"" + memberId + "\"";
        return mvc.perform(post("/internal/auth/staff/accounts").header("Authorization", STAFF_SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","displayName":"Staff Person","roles":["%s"],"password":"%s"%s}"""
                        .formatted(email, role, password, member)));
    }

    private ResultActions logIn(String email, String password) throws Exception {
        return logIn(email, password, HOME);
    }

    private ResultActions logIn(String email, String password, String clientAddress) throws Exception {
        return mvc.perform(post("/internal/auth/staff/password/login").header("Authorization", STAFF_SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","clientAddress":"%s"}""".formatted(email, password, clientAddress)));
    }

    private int failures(UUID staffId, String source) {
        return jdbc.sql("SELECT coalesce(max(failures), 0) FROM identity.staff_login_failure WHERE staff_id = :id AND source = :s")
                .param("id", staffId).param("s", source).query(Integer.class).single();
    }

    private int auditEntries(UUID subject, String entryType) {
        return jdbc.sql("SELECT count(*) FROM governance.audit_entry WHERE subject_id = :id AND entry_type = :t")
                .param("id", subject).param("t", entryType).query(Integer.class).single();
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
