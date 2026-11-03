package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentGrant;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.identity.api.events.IdentityEvents.ConsentRevoked;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sharing the record needs the member's consent, and withdrawing it ends every link (ADR 0025), over HTTP and through
 * the real identity events against PostgreSQL.
 */
@TestPropertySource(properties = "seed.record.signing-secret=" + RecordShareConsentIT.SECRET)
class RecordShareConsentIT extends IntegrationTest {

    static final String SECRET = "it-consent-signing-secret-0123456789abcdef";
    private static final String MEMBER = "X-Seed-Member-Id";
    private static final String SHARES = "/api/v1/members/me/record/shares";

    @Autowired
    ObjectMapper json;
    @Autowired
    IdentityFacade identity;
    @Autowired
    CreditRecordFacade creditRecord;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    TransactionTemplate tx;

    @Test
    void aLinkNeedsTheConsentAndTheProblemIsStable() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = registered();
        withRecord(ana);

        mvc.perform(post(SHARES).header(MEMBER, ana.toString()).header("Idempotency-Key", "no-consent")
                        .contentType(MediaType.APPLICATION_JSON).content(body(7, "SUMMARY")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("record-sharing-consent-required"))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("record-sharing-consent-required")));
        assertThat(sharesOf(ana)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM creditrecord.record_snapshot WHERE member_id = ?").param(ana).query(Integer.class).single())
                .isZero();

        grant(ana);
        String token = create(ana, "with-consent", 7, "SUMMARY");
        assertThat(verifyStatus(token)).isEqualTo(200);
    }

    @Test
    void aMemberIdentityDoesNotKnowCannotShareEither() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID stranger = UUID.randomUUID();
        withRecord(stranger);

        mvc.perform(post(SHARES).header(MEMBER, stranger.toString()).header("Idempotency-Key", "stranger")
                        .contentType(MediaType.APPLICATION_JSON).content(body(7, "LEVEL")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("record-sharing-consent-required"));
    }

    @Test
    void anAgreementToAnOlderTextDoesNotCount() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = registered();
        withRecord(ana);
        jdbc.sql("INSERT INTO identity.consent (id, member_id, type, version, granted_at) VALUES (?, ?, 'RECORD_SHARING', '2019-01', now())")
                .params(Ids.newId(), ana).update();

        mvc.perform(post(SHARES).header(MEMBER, ana.toString()).header("Idempotency-Key", "old-text")
                        .contentType(MediaType.APPLICATION_JSON).content(body(7, "LEVEL")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("record-sharing-consent-required"));
    }

    @Test
    void anotherKindOfConsentIsNotConsentToShare() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = registered();
        withRecord(ana);
        identity.grantConsent(ana, new ConsentGrant(ConsentType.PRIVACY, null));
        identity.grantConsent(ana, new ConsentGrant(ConsentType.INCOME_READ, null));

        mvc.perform(post(SHARES).header(MEMBER, ana.toString()).header("Idempotency-Key", "other")
                        .contentType(MediaType.APPLICATION_JSON).content(body(7, "LEVEL")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("record-sharing-consent-required"));
    }

    @Test
    void withdrawingTheConsentRevokesEveryLinkAndThePartnerSeesTheSameAnswerAsForAnyUnknownLink() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = registered();
        UUID ben = registered();
        withRecord(ana);
        withRecord(ben);
        grant(ana);
        grant(ben);
        String first = create(ana, "one", 7, "LEVEL");
        String second = create(ana, "two", 30, "FULL");
        String bens = create(ben, "ben-one", 7, "SUMMARY");
        assertThat(verifyStatus(first)).isEqualTo(200);

        mvc.perform(delete("/api/v1/members/me/consents/RECORD_SHARING").header(MEMBER, ana.toString())).andExpect(status().isNoContent());
        settle();

        String revoked = mvc.perform(get("/api/v1/records/verify/{token}", first)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("record-not-found")).andReturn().getResponse().getContentAsString();
        String revokedToo = mvc.perform(get("/api/v1/records/verify/{token}", second)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String garbage = mvc.perform(get("/api/v1/records/verify/{token}", "v1.k1.AAAA.AAAA")).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(revoked).isEqualTo(garbage).isEqualTo(revokedToo);
        assertThat(revoked.toLowerCase()).doesNotContain("consent").doesNotContain("revoked");

        assertThat(sharesOf(ana)).hasSize(2).allSatisfy(s -> assertThat(s.path("status").asText()).isEqualTo("REVOKED"));
        assertThat(verifyStatus(bens)).as("another member's link is untouched").isEqualTo(200);
        assertThat(sharesOf(ben).get(0).path("status").asText()).isEqualTo("ACTIVE");

        // No new link either, until the member agrees again; the old ones stay dead.
        mvc.perform(post(SHARES).header(MEMBER, ana.toString()).header("Idempotency-Key", "after")
                        .contentType(MediaType.APPLICATION_JSON).content(body(7, "LEVEL")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("record-sharing-consent-required"));
        grant(ana);
        String fresh = create(ana, "after", 7, "LEVEL");
        assertThat(verifyStatus(fresh)).isEqualTo(200);
        assertThat(verifyStatus(first)).isEqualTo(404);
    }

    @Test
    void theRevocationIsIdempotentWhenTheEventArrivesAgain() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = registered();
        withRecord(ana);
        grant(ana);
        String token = create(ana, "one", 7, "SUMMARY");
        identity.revokeConsent(ana, ConsentType.RECORD_SHARING);
        settle();
        Instant revokedAt = Instant.parse(sharesOf(ana).get(0).path("revokedAt").asText());

        // At-least-once delivery: the same fact again, and a different consent's withdrawal, change nothing.
        clock.set(LocalDate.of(2026, 11, 12));
        UUID consentId = jdbc.sql("SELECT id FROM identity.consent WHERE member_id = ? AND type = 'RECORD_SHARING'")
                .param(ana).query(UUID.class).single();
        for (int i = 0; i < 2; i++) {
            tx.executeWithoutResult(s -> publisher.publishEvent(new ConsentRevoked(Ids.newId(), ana, consentId, ConsentType.RECORD_SHARING,
                    "2026-11", Instant.now())));
        }
        settle();

        assertThat(Instant.parse(sharesOf(ana).get(0).path("revokedAt").asText())).isEqualTo(revokedAt);
        assertThat(creditRecord.revokeAllShares(ana)).isZero();
        assertThat(verifyStatus(token)).isEqualTo(404);
    }

    @Test
    void withdrawingAnotherConsentLeavesTheLinksAlone() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = registered();
        withRecord(ana);
        grant(ana);
        identity.grantConsent(ana, new ConsentGrant(ConsentType.INCOME_READ, null));
        String token = create(ana, "one", 7, "LEVEL");

        identity.revokeConsent(ana, ConsentType.INCOME_READ);
        settle();

        assertThat(verifyStatus(token)).isEqualTo(200);
        assertThat(sharesOf(ana).get(0).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void aReplayedCreateAfterWithdrawalDoesNotHandTheTokenOutAgain() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = registered();
        withRecord(ana);
        grant(ana);
        create(ana, "same-key", 7, "LEVEL");
        identity.revokeConsent(ana, ConsentType.RECORD_SHARING);
        settle();

        mvc.perform(post(SHARES).header(MEMBER, ana.toString()).header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body(7, "LEVEL")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("record-sharing-consent-required"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private UUID registered() {
        return identity.loginWithPayPal(new PayPalLogin("PAYER-" + UUID.randomUUID(), "member@example.test", "Member", true, "en"))
                .member().memberId();
    }

    private void grant(UUID member) {
        identity.grantConsent(member, new ConsentGrant(ConsentType.RECORD_SHARING, null));
    }

    private void withRecord(UUID member) {
        for (int month = 1; month <= 3; month++) {
            creditRecord.ingest(RecordFact.savingsDeposit("consent-" + member + "-" + month, member, LocalDate.of(2026, month, 5)));
        }
    }

    private static String body(int days, String detail) {
        return "{\"expiresInDays\":" + days + ",\"detail\":\"" + detail + "\"}";
    }

    private String create(UUID member, String key, int days, String detail) throws Exception {
        String response = mvc.perform(post(SHARES).header(MEMBER, member.toString()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(days, detail)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("token").asText();
    }

    private int verifyStatus(String token) throws Exception {
        return mvc.perform(get("/api/v1/records/verify/{token}", token)).andReturn().getResponse().getStatus();
    }

    private JsonNode sharesOf(UUID member) throws Exception {
        return json.readTree(mvc.perform(get(SHARES).header(MEMBER, member.toString())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }
}
