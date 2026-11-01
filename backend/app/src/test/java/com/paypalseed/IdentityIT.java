package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.MemberView;
import com.paypalseed.identity.api.dto.Language;
import com.paypalseed.identity.api.dto.WalletPreference;
import com.paypalseed.identity.api.events.IdentityEvents.ConsentGranted;
import com.paypalseed.identity.api.events.IdentityEvents.ConsentRevoked;
import com.paypalseed.identity.api.events.IdentityEvents.IdentityEvent;
import com.paypalseed.identity.api.events.IdentityEvents.MemberProfileChanged;
import com.paypalseed.identity.api.events.IdentityEvents.MemberRegistered;
import com.paypalseed.identity.application.usecases.StaffService;
import com.paypalseed.platform.security.Actor.Role;
import com.paypalseed.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Members, staff and consents over HTTP against real PostgreSQL. The {@code demo} profile is on so the demo staff
 * seeding is exercised; it only adds the three staff rows.
 */
@ActiveProfiles("demo")
@Import(IdentityIT.Capture.class)
class IdentityIT extends IntegrationTest {

    private static final LocalDate DAY_ONE = LocalDate.of(2026, 11, 10);
    private static final UUID AIRA = UUID.fromString("0b5e1f4a-6d2c-4c7e-9a1b-3f6e2d8c4a01");
    private static final UUID PRIYAM = UUID.fromString("0b5e1f4a-6d2c-4c7e-9a1b-3f6e2d8c4a02");
    private static final UUID PRAJNA = UUID.fromString("0b5e1f4a-6d2c-4c7e-9a1b-3f6e2d8c4a03");

    static class EventLog {
        final List<IdentityEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(IdentityEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {
        @Bean
        EventLog eventLog() {
            return new EventLog();
        }
    }

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    IdentityFacade identity;
    @Autowired
    EventLog log;
    @Autowired
    StaffService staffService;

    @BeforeEach
    void startOfDay() {
        clock.set(DAY_ONE);
        log.events.clear();
    }

    // helpers

    private ResultActions login(String payerId, String body) throws Exception {
        return mvc.perform(post("/internal/members/login").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", SERVICE_TOKEN));
    }

    private UUID loginAs(String payerId, String email, String name) throws Exception {
        String body = "{\"paypalPayerId\":\"" + payerId + "\",\"email\":\"" + email + "\",\"name\":\"" + name
                + "\",\"verified\":true}";
        String response = login(payerId, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("member").get("memberId").asText());
    }

    private static MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request, UUID memberId) {
        return request.header("X-Seed-Member-Id", memberId.toString());
    }

    private static MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request, UUID staffId, String roles) {
        return request.header("X-Seed-Staff-Id", staffId.toString()).header("X-Seed-Staff-Roles", roles);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    // login

    @Test
    void firstLoginCreatesTheMemberAndALaterLoginReturnsTheSameOne() throws Exception {
        String payer = unique("PAYER");
        String body = "{\"paypalPayerId\":\"" + payer + "\",\"email\":\"ana@example.org\",\"name\":\"Ana Torres\","
                + "\"verified\":true,\"locale\":\"es-MX\"}";

        String first = login(payer, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.member.language").value("es"))
                .andExpect(jsonPath("$.member.walletPreference").value("PAYPAL"))
                .andExpect(jsonPath("$.member.paypalVerified").value(true))
                .andExpect(jsonPath("$.member.email").value("ana@example.org"))
                .andReturn().getResponse().getContentAsString();
        UUID memberId = UUID.fromString(json.readTree(first).get("member").get("memberId").asText());
        MemberView created = identity.member(memberId).orElseThrow();

        clock.set(DAY_ONE.plusDays(3));
        String second = login(payer, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.member.memberId").value(memberId.toString()))
                .andReturn().getResponse().getContentAsString();

        MemberView later = identity.member(memberId).orElseThrow();
        assertThat(json.readTree(second).get("member").get("memberId").asText()).isEqualTo(memberId.toString());
        assertThat(later.lastLoginAt()).isAfter(created.lastLoginAt());
        assertThat(later.createdAt()).isEqualTo(created.createdAt());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM identity.member WHERE paypal_payer_id = ?").param(payer).query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(identity.memberIdForPayer(payer)).contains(memberId);
        assertThat(log.events).filteredOn(e -> e instanceof MemberRegistered && e.memberId().equals(memberId)).hasSize(1);
    }

    @Test
    void payerIdAndPayoutRecipientComeFromTheLoginRecord() throws Exception {
        String payer = unique("PAYER");
        UUID memberId = loginAs(payer, "ana@example.org", "Ana");

        assertThat(identity.paypalPayerId(memberId)).contains(payer);
        assertThat(identity.payoutRecipient(memberId)).hasValueSatisfying(r -> {
            assertThat(r.payerId()).isEqualTo(payer);
            assertThat(r.email()).isEqualTo("ana@example.org");
        });
        assertThat(identity.paypalPayerId(UUID.randomUUID())).isEmpty();
        assertThat(identity.payoutRecipient(UUID.randomUUID())).isEmpty();
    }

    @Test
    void twoPayerIdsAreTwoMembers() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");
        UUID ben = loginAs(unique("PAYER"), "ben@example.org", "Ben");

        assertThat(ana).isNotEqualTo(ben);
        assertThat(identity.member(ana).orElseThrow().email()).isEqualTo("ana@example.org");
        assertThat(identity.member(ben).orElseThrow().email()).isEqualTo("ben@example.org");
    }

    @Test
    void aLoginWithoutDetailsNeverErasesStoredOnesOrDowngradesTheLanguage() throws Exception {
        String payer = unique("PAYER");
        login(payer, "{\"paypalPayerId\":\"" + payer + "\",\"email\":\"ana@example.org\",\"name\":\"Ana\",\"locale\":\"en\"}")
                .andExpect(status().isOk());
        UUID memberId = identity.memberIdForPayer(payer).orElseThrow();
        send(asMember(patch("/api/v1/members/me"), memberId), "{\"language\":\"es\"}").andExpect(status().isOk());

        login(payer, "{\"paypalPayerId\":\"" + payer + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.member.language").value("es"))
                .andExpect(jsonPath("$.member.email").value("ana@example.org"))
                .andExpect(jsonPath("$.member.displayName").value("Ana"));
        login(payer, "{\"paypalPayerId\":\"" + payer + "\",\"locale\":\"pt-BR\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.member.language").value("es"));
    }

    @Test
    void anUnsupportedLocaleOnFirstLoginFallsBackToEnglish() throws Exception {
        String payer = unique("PAYER");

        login(payer, "{\"paypalPayerId\":\"" + payer + "\",\"locale\":\"fr-FR\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.member.language").value("en"))
                .andExpect(jsonPath("$.member.email").doesNotExist());
    }

    @Test
    void loginValidatesItsBody() throws Exception {
        login("x", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation-failed"));
        login("x", "{\"paypalPayerId\":\"   \"}").andExpect(status().isBadRequest());
        login("x", "{\"paypalPayerId\":\"" + "p".repeat(65) + "\"}").andExpect(status().isBadRequest());
        login("x", "{\"paypalPayerId\":\"P1\",\"email\":\"not-an-email\"}").andExpect(status().isBadRequest());
    }

    @Test
    void internalRoutesNeedTheServiceToken() throws Exception {
        String body = "{\"paypalPayerId\":\"" + unique("PAYER") + "\"}";
        String staffBody = "{\"displayName\":\"X\",\"roles\":[\"OFFICER\"]}";
        UUID someone = UUID.randomUUID();

        // no credentials
        send(post("/internal/members/login"), body).andExpect(status().isUnauthorized());
        send(put("/internal/staff/{id}", someone), staffBody).andExpect(status().isUnauthorized());
        // a wrong token
        send(post("/internal/members/login").header("Authorization", "Bearer wrong-token-0123456789"), body)
                .andExpect(status().isUnauthorized());
        // a member or an officer is not the service
        send(asMember(post("/internal/members/login"), someone), body).andExpect(status().isForbidden());
        send(asStaff(post("/internal/members/login"), someone, "ADMIN"), body).andExpect(status().isForbidden());
        send(asStaff(put("/internal/staff/{id}", someone), someone, "ADMIN"), staffBody).andExpect(status().isForbidden());
        assertThat(identity.staff(someone)).isEmpty();
    }

    // profile

    @Test
    void aMemberReadsAndChangesOnlyTheirOwnProfile() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");
        UUID ben = loginAs(unique("PAYER"), "ben@example.org", "Ben");

        mvc.perform(asMember(get("/api/v1/members/me"), ana)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(ana.toString()))
                .andExpect(jsonPath("$.email").value("ana@example.org"))
                .andExpect(jsonPath("$.paypalPayerId").doesNotExist());
        mvc.perform(asMember(get("/api/v1/members/me"), ben)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ben@example.org"));

        send(asMember(patch("/api/v1/members/me"), ana), "{\"language\":\"ES\",\"walletPreference\":\"venmo\",\"displayName\":\"Ana T\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(ana.toString()))
                .andExpect(jsonPath("$.language").value("es"))
                .andExpect(jsonPath("$.walletPreference").value("VENMO"))
                .andExpect(jsonPath("$.displayName").value("Ana T"));

        MemberView untouched = identity.member(ben).orElseThrow();
        assertThat(untouched.language()).isEqualTo(Language.EN);
        assertThat(untouched.walletPreference()).isEqualTo(WalletPreference.PAYPAL);
        assertThat(untouched.displayName()).isEqualTo("Ben");
        assertThat(log.events).filteredOn(e -> e instanceof MemberProfileChanged && e.memberId().equals(ana)).hasSize(1);
        assertThat(log.events).filteredOn(e -> e.memberId().equals(ben) && e instanceof MemberProfileChanged).isEmpty();
    }

    @Test
    void anEmptyOrUnchangedPatchChangesNothingAndPublishesNothing() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");
        log.events.clear();

        send(asMember(patch("/api/v1/members/me"), ana), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("en"));
        send(asMember(patch("/api/v1/members/me"), ana), "{\"language\":\"en\",\"walletPreference\":\"PAYPAL\"}")
                .andExpect(status().isOk());

        assertThat(log.events).isEmpty();
    }

    @Test
    void profileValuesAreValidated() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");

        send(asMember(patch("/api/v1/members/me"), ana), "{\"language\":\"fr\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("unsupported-language"));
        send(asMember(patch("/api/v1/members/me"), ana), "{\"walletPreference\":\"CASH\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("unknown-wallet-preference"));
        send(asMember(patch("/api/v1/members/me"), ana), "{\"displayName\":\"  \"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid-name"));
        send(asMember(patch("/api/v1/members/me"), ana), "{\"displayName\":\"" + "n".repeat(121) + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(identity.member(ana).orElseThrow().language()).isEqualTo(Language.EN);
    }

    @Test
    void aSignedInIdWithoutARecordIsNotFound() throws Exception {
        UUID stranger = UUID.randomUUID();

        mvc.perform(asMember(get("/api/v1/members/me"), stranger)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("member-not-found"));
        send(asMember(patch("/api/v1/members/me"), stranger), "{\"language\":\"es\"}").andExpect(status().isNotFound());
        mvc.perform(asMember(get("/api/v1/members/me/consents"), stranger)).andExpect(status().isNotFound());
        send(asMember(post("/api/v1/members/me/consents"), stranger), "{\"type\":\"TERMS\"}").andExpect(status().isNotFound());
        mvc.perform(asMember(delete("/api/v1/members/me/consents/TERMS"), stranger)).andExpect(status().isNotFound());
    }

    @Test
    void routesCheckTheRoleAndTheCaller() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");
        UUID officer = UUID.randomUUID();

        // not signed in
        mvc.perform(get("/api/v1/members/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/members/me/consents")).andExpect(status().isUnauthorized());
        send(patch("/api/v1/members/me"), "{}").andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/desk/staff")).andExpect(status().isUnauthorized());
        // staff and the service are not members
        mvc.perform(asStaff(get("/api/v1/members/me"), officer, "OFFICER")).andExpect(status().isForbidden());
        // the service token opens /internal/** only (ADR 0016), so on /api/** it is no credential at all
        mvc.perform(get("/api/v1/members/me").header("Authorization", SERVICE_TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(asStaff(get("/api/v1/members/me/consents"), officer, "ADMIN")).andExpect(status().isForbidden());
        // a member is not staff, and a staff header without a staff role grants nothing
        mvc.perform(asMember(get("/api/v1/desk/staff"), ana)).andExpect(status().isForbidden());
        mvc.perform(asStaff(get("/api/v1/desk/staff"), officer, "")).andExpect(status().isForbidden());
        mvc.perform(asStaff(get("/api/v1/desk/staff"), officer, "MEMBER,SERVICE")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/desk/staff").header("Authorization", SERVICE_TOKEN)).andExpect(status().isUnauthorized());
    }

    // consents

    @Test
    void grantThenRevokeThenListShowsTheWholeLedger() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");
        UUID ben = loginAs(unique("PAYER"), "ben@example.org", "Ben");
        mvc.perform(asMember(get("/api/v1/members/me/consents"), ana)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String granted = send(asMember(post("/api/v1/members/me/consents"), ana), "{\"type\":\"INCOME_READ\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INCOME_READ"))
                .andExpect(jsonPath("$.version").value("2026-11"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.revokedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String consentId = json.readTree(granted).get("consentId").asText();
        assertThat(identity.hasActiveConsent(ana, ConsentType.INCOME_READ)).isTrue();
        assertThat(identity.hasActiveConsent(ben, ConsentType.INCOME_READ)).isFalse();

        clock.set(DAY_ONE.plusDays(1));
        mvc.perform(asMember(delete("/api/v1/members/me/consents/income_read"), ana)).andExpect(status().isNoContent());
        assertThat(identity.hasActiveConsent(ana, ConsentType.INCOME_READ)).isFalse();

        clock.set(DAY_ONE.plusDays(2));
        send(asMember(post("/api/v1/members/me/consents"), ana), "{\"type\":\"INCOME_READ\",\"version\":\"2026-11\"}")
                .andExpect(status().isCreated());
        send(asMember(post("/api/v1/members/me/consents"), ana), "{\"type\":\"TERMS\"}").andExpect(status().isCreated());

        mvc.perform(asMember(get("/api/v1/members/me/consents"), ana)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.consentId == '" + consentId + "')].active").value(false))
                .andExpect(jsonPath("$[?(@.consentId == '" + consentId + "')].revokedAt").isNotEmpty())
                .andExpect(jsonPath("$[?(@.type == 'INCOME_READ' && @.active == true)]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.type == 'TERMS' && @.active == true)]").isNotEmpty());
        // another member sees none of it
        mvc.perform(asMember(get("/api/v1/members/me/consents"), ben)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(log.events).filteredOn(e -> e instanceof ConsentGranted && e.memberId().equals(ana)).hasSize(3);
        assertThat(log.events).filteredOn(e -> e instanceof ConsentRevoked && e.memberId().equals(ana)).hasSize(1);
    }

    @Test
    void grantingTheSameConsentTwiceIsAReplayAndRevokingTwiceIsANoOp() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");

        String first = send(asMember(post("/api/v1/members/me/consents"), ana), "{\"type\":\"PRIVACY\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = send(asMember(post("/api/v1/members/me/consents"), ana), "{\"type\":\"PRIVACY\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode a = json.readTree(first);
        JsonNode b = json.readTree(replay);
        assertThat(b.get("consentId").asText()).isEqualTo(a.get("consentId").asText());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM identity.consent WHERE member_id = ?").param(ana).query(Integer.class).single())
                .isEqualTo(1);

        mvc.perform(asMember(delete("/api/v1/members/me/consents/PRIVACY"), ana)).andExpect(status().isNoContent());
        mvc.perform(asMember(delete("/api/v1/members/me/consents/PRIVACY"), ana)).andExpect(status().isNoContent());
        mvc.perform(asMember(delete("/api/v1/members/me/consents/RECORD_SHARING"), ana)).andExpect(status().isNoContent());
        assertThat(log.events).filteredOn(e -> e instanceof ConsentRevoked && e.memberId().equals(ana)).hasSize(1);
    }

    @Test
    void consentInputIsValidated() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");

        send(asMember(post("/api/v1/members/me/consents"), ana), "{\"type\":\"MARKETING\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("unknown-consent-type"));
        send(asMember(post("/api/v1/members/me/consents"), ana), "{}").andExpect(status().isBadRequest());
        send(asMember(post("/api/v1/members/me/consents"), ana), "{\"type\":\"TERMS\",\"version\":\"1999-01\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("consent-version-stale"))
                .andExpect(jsonPath("$.details.currentVersion").value("2026-11"));
        mvc.perform(asMember(delete("/api/v1/members/me/consents/MARKETING"), ana)).andExpect(status().isUnprocessableEntity());

        assertThat(identity.consents(ana)).isEmpty();
    }

    @Test
    void aNewVersionClosesTheOldLineAndAppendsOne() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");
        jdbc.sql("INSERT INTO identity.consent (id, member_id, type, version, granted_at) VALUES (?, ?, 'TERMS', '2025-01', '2025-01-01T00:00:00Z')")
                .param(UUID.randomUUID()).param(ana).update();
        assertThat(identity.hasActiveConsent(ana, ConsentType.TERMS)).isTrue();

        send(asMember(post("/api/v1/members/me/consents"), ana), "{\"type\":\"TERMS\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("2026-11"));

        assertThat(identity.consents(ana)).hasSize(2);
        assertThat(identity.consents(ana)).filteredOn(c -> c.active()).extracting(c -> c.version()).containsExactly("2026-11");
        assertThat(identity.consents(ana)).filteredOn(c -> !c.active()).extracting(c -> c.version()).containsExactly("2025-01");
    }

    @Test
    void theConsentLedgerCannotBeEditedOrDeletedInTheDatabase() throws Exception {
        UUID ana = loginAs(unique("PAYER"), "ana@example.org", "Ana");
        send(asMember(post("/api/v1/members/me/consents"), ana), "{\"type\":\"TERMS\"}").andExpect(status().isCreated());

        assertThatThrownBy(() -> jdbc.sql("UPDATE identity.consent SET version = 'forged' WHERE member_id = ?").param(ana).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE identity.consent SET granted_at = granted_at - interval '1 day' WHERE member_id = ?")
                .param(ana).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM identity.consent WHERE member_id = ?").param(ana).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE identity.consent").update()).hasMessageContaining("append-only");

        mvc.perform(asMember(delete("/api/v1/members/me/consents/TERMS"), ana)).andExpect(status().isNoContent());
        assertThatThrownBy(() -> jdbc.sql("UPDATE identity.consent SET revoked_at = NULL WHERE member_id = ?").param(ana).update())
                .as("a closed line cannot be reopened").hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE identity.consent SET revoked_at = now() + interval '1 day' WHERE member_id = ?")
                .param(ana).update()).as("a closed line cannot be changed again").hasMessageContaining("append-only");
    }

    // staff

    @Test
    void staffAreUpsertedByTheServiceAndListedForOfficers() throws Exception {
        UUID id = UUID.randomUUID();
        String body = "{\"displayName\":\"Marta Lopez\",\"email\":\"marta@example.org\",\"roles\":[\"officer\",\"CHECKER\"]}";

        send(put("/internal/staff/{id}", id).header("Authorization", SERVICE_TOKEN), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.staffId").value(id.toString()))
                .andExpect(jsonPath("$.displayName").value("Marta Lopez"))
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.active").value(true));
        // the same call again changes nothing; a different body replaces the record
        send(put("/internal/staff/{id}", id).header("Authorization", SERVICE_TOKEN), body).andExpect(status().isOk());
        send(put("/internal/staff/{id}", id).header("Authorization", SERVICE_TOKEN),
                "{\"displayName\":\"Marta L. Lopez\",\"roles\":[\"ADMIN\"],\"active\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Marta L. Lopez"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.email").doesNotExist());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM identity.staff WHERE id = ?").param(id).query(Integer.class).single()).isEqualTo(1);

        for (String roles : List.of("OFFICER", "CHECKER", "ADMIN", "CHECKER,OFFICER")) {
            mvc.perform(asStaff(get("/api/v1/desk/staff"), UUID.randomUUID(), roles)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.staffId == '" + id + "')].displayName").value("Marta L. Lopez"));
        }
    }

    @Test
    void inactiveStaffAreHiddenFromTheListButKeepTheirNames() throws Exception {
        UUID id = UUID.randomUUID();
        send(put("/internal/staff/{id}", id).header("Authorization", SERVICE_TOKEN),
                "{\"displayName\":\"Left Company\",\"roles\":[\"OFFICER\"],\"active\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(asStaff(get("/api/v1/desk/staff"), AIRA, "OFFICER")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.staffId == '" + id + "')]").isEmpty());
        mvc.perform(asStaff(get("/api/v1/desk/staff").param("includeInactive", "true"), AIRA, "OFFICER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.staffId == '" + id + "')]").isNotEmpty());
        assertThat(identity.staffDisplayNames(List.of(id))).containsEntry(id, "Left Company");
    }

    @Test
    void staffInputIsValidated() throws Exception {
        UUID id = UUID.randomUUID();
        String auth = SERVICE_TOKEN;

        send(put("/internal/staff/{id}", id).header("Authorization", auth), "{\"displayName\":\"X\",\"roles\":[]}")
                .andExpect(status().isBadRequest());
        send(put("/internal/staff/{id}", id).header("Authorization", auth), "{\"displayName\":\"X\",\"roles\":[\"MEMBER\"]}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("invalid-staff-roles"));
        send(put("/internal/staff/{id}", id).header("Authorization", auth), "{\"displayName\":\"X\",\"roles\":[\"SERVICE\"]}")
                .andExpect(status().isUnprocessableEntity());
        send(put("/internal/staff/{id}", id).header("Authorization", auth), "{\"displayName\":\"X\",\"roles\":[\"CEO\"]}")
                .andExpect(status().isUnprocessableEntity());
        send(put("/internal/staff/{id}", id).header("Authorization", auth), "{\"displayName\":\"\",\"roles\":[\"OFFICER\"]}")
                .andExpect(status().isBadRequest());
        mvc.perform(put("/internal/staff/not-a-uuid").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"X\",\"roles\":[\"OFFICER\"]}")).andExpect(status().isBadRequest());

        assertThat(identity.staff(id)).isEmpty();
    }

    @Test
    void staffDisplayNamesResolveManyIdsAndSkipUnknownOnes() {
        UUID unknown = UUID.randomUUID();

        assertThat(identity.staffDisplayNames(List.of(AIRA, PRIYAM, unknown)))
                .containsOnlyKeys(AIRA, PRIYAM)
                .containsEntry(AIRA, "Aira Jena")
                .containsEntry(PRIYAM, "Priyam Priyanshu Pradhan");
        assertThat(identity.staffDisplayNames(List.of())).isEmpty();
    }

    // demo seeding

    @Test
    void theDemoProfileSeedsTheThreeDemoStaff() throws Exception {
        mvc.perform(asStaff(get("/api/v1/desk/staff"), AIRA, "OFFICER")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.staffId == '" + AIRA + "')].displayName").value("Aira Jena"))
                .andExpect(jsonPath("$[?(@.staffId == '" + PRIYAM + "')].displayName").value("Priyam Priyanshu Pradhan"))
                .andExpect(jsonPath("$[?(@.staffId == '" + PRAJNA + "')].displayName").value("Prajna Jeet Ojha"));

        assertThat(identity.staff(AIRA).orElseThrow().roles()).extracting(Enum::name).containsExactly("OFFICER");
        assertThat(identity.staff(PRIYAM).orElseThrow().roles()).extracting(Enum::name).containsExactly("CHECKER");
        assertThat(identity.staff(PRAJNA).orElseThrow().roles()).extracting(Enum::name)
                .containsExactlyInAnyOrder("OFFICER", "CHECKER", "ADMIN");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM identity.staff WHERE id IN (?, ?, ?)").param(AIRA).param(PRIYAM).param(PRAJNA)
                .query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void seedingAgainAddsNothingAndKeepsWhatIsStored() {
        boolean added = staffService.seed(AIRA, "Someone Else", Set.of(Role.ADMIN));

        assertThat(added).isFalse();
        assertThat(identity.staff(AIRA).orElseThrow().displayName()).isEqualTo("Aira Jena");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM identity.staff WHERE id = ?").param(AIRA).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void noPersonalDataLeavesInEvents() throws Exception {
        String payer = unique("PAYER");
        login(payer, "{\"paypalPayerId\":\"" + payer + "\",\"email\":\"private@example.org\",\"name\":\"Private Person\"}")
                .andExpect(status().isOk());

        assertThat(log.events).isNotEmpty();
        assertThat(log.events.toString()).doesNotContain("private@example.org", "Private Person", payer);
    }
}
