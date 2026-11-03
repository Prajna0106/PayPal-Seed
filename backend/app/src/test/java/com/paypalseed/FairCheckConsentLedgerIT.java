package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentGrant;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The fair-check consent that underwriting enforces is also a line on the member's consent list in identity, and the
 * two stay in step both ways (ADR 0026): giving it records it, and taking either line off the list withdraws it.
 */
class FairCheckConsentLedgerIT extends MoneyFlowSupport {

    @Autowired
    UnderwritingFacade underwriting;

    private void giveFairCheckConsent(UUID member) throws Exception {
        mvc.perform(asMember(post("/api/v1/underwriting/consent"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.consented").value(true));
    }

    private JsonNode list(UUID member) throws Exception {
        return read(mvc.perform(asMember(get("/api/v1/members/me/consents"), member)).andExpect(status().isOk()).andReturn());
    }

    private long lines(UUID member, String type, boolean active) {
        return jdbc.sql("SELECT COUNT(*) FROM identity.consent WHERE member_id = :m AND type = :t AND (revoked_at IS NULL) = :a")
                .param("m", member).param("t", type).param("a", active).query(Long.class).single();
    }

    @Test
    void givingTheFairCheckConsentPutsAnActiveLineOnTheMembersListAndRepeatingItAddsNothing() throws Exception {
        UUID ana = newMember();

        giveFairCheckConsent(ana);
        giveFairCheckConsent(ana);
        underwriting.grantConsent(ana);

        JsonNode consents = list(ana);
        assertThat(consents).hasSize(1);
        assertThat(consents.get(0).path("type").asText()).isEqualTo("FAIR_CHECK");
        assertThat(consents.get(0).path("active").asBoolean()).isTrue();
        assertThat(consents.get(0).path("version").asText()).isEqualTo("2026-11");
        assertThat(lines(ana, "FAIR_CHECK", true)).isEqualTo(1);
        // the other lines are the member's own choices and are not recorded for them
        assertThat(identity.hasActiveConsent(ana, ConsentType.INCOME_READ)).isFalse();
    }

    @Test
    void aMemberWithoutAnIdentityRecordCanStillConsentAndNothingIsListed() {
        UUID noRecord = UUID.randomUUID();

        assertThat(underwriting.grantConsent(noRecord).consented()).isTrue();
        assertThat(underwriting.revokeConsent(noRecord).consented()).isFalse();

        assertThat(jdbc.sql("SELECT COUNT(*) FROM identity.consent WHERE member_id = :m").param("m", noRecord)
                .query(Long.class).single()).isZero();
    }

    @Test
    void takingTheFairCheckLineOffTheListWithdrawsTheUnderwritingConsentAndCloseTheIncomeLineToo() throws Exception {
        UUID ana = newMember();
        identity.grantConsent(ana, new ConsentGrant(ConsentType.INCOME_READ, null));
        giveFairCheckConsent(ana);
        settle();

        mvc.perform(asMember(delete("/api/v1/members/me/consents/FAIR_CHECK"), ana)).andExpect(status().isNoContent());
        settle();

        assertThat(underwriting.getConsentStatus(ana).consented()).isFalse();
        assertThat(identity.hasActiveConsent(ana, ConsentType.FAIR_CHECK)).isFalse();
        assertThat(identity.hasActiveConsent(ana, ConsentType.INCOME_READ)).isFalse();
        // each line is closed exactly once: the two closings do not chase each other
        assertThat(lines(ana, "FAIR_CHECK", false)).isEqualTo(1);
        assertThat(lines(ana, "INCOME_READ", false)).isEqualTo(1);
        assertThat(lines(ana, "FAIR_CHECK", true)).isZero();
        assertThat(lines(ana, "INCOME_READ", true)).isZero();
    }

    @Test
    void takingTheIncomeLineOffTheListStillWithdrawsBothConsents() throws Exception {
        UUID ana = newMember();
        identity.grantConsent(ana, new ConsentGrant(ConsentType.INCOME_READ, null));
        giveFairCheckConsent(ana);
        settle();
        assertThat(identity.hasActiveConsent(ana, ConsentType.FAIR_CHECK)).isTrue();

        mvc.perform(asMember(delete("/api/v1/members/me/consents/INCOME_READ"), ana)).andExpect(status().isNoContent());
        settle();

        assertThat(underwriting.getConsentStatus(ana).consented()).isFalse();
        assertThat(identity.hasActiveConsent(ana, ConsentType.FAIR_CHECK)).isFalse();
        assertThat(lines(ana, "FAIR_CHECK", false)).isEqualTo(1);
        assertThat(lines(ana, "INCOME_READ", false)).isEqualTo(1);
    }

    @Test
    void withdrawingInUnderwritingClosesTheLinesOnTheList() throws Exception {
        UUID ana = newMember();
        identity.grantConsent(ana, new ConsentGrant(ConsentType.INCOME_READ, null));
        giveFairCheckConsent(ana);

        mvc.perform(asMember(delete("/api/v1/underwriting/consent"), ana)).andExpect(status().isOk())
                .andExpect(jsonPath("$.consented").value(false));
        settle();

        assertThat(identity.hasActiveConsent(ana, ConsentType.FAIR_CHECK)).isFalse();
        assertThat(identity.hasActiveConsent(ana, ConsentType.INCOME_READ)).isFalse();
        assertThat(lines(ana, "FAIR_CHECK", false)).isEqualTo(1);
    }

    @Test
    void consentingAgainAfterAWithdrawalAppendsANewLine() throws Exception {
        UUID ana = newMember();
        giveFairCheckConsent(ana);
        mvc.perform(asMember(delete("/api/v1/members/me/consents/FAIR_CHECK"), ana)).andExpect(status().isNoContent());
        settle();

        giveFairCheckConsent(ana);

        assertThat(underwriting.getConsentStatus(ana).consented()).isTrue();
        assertThat(lines(ana, "FAIR_CHECK", false)).isEqualTo(1);
        assertThat(lines(ana, "FAIR_CHECK", true)).isEqualTo(1);
    }

    @Test
    void agreeingToTheFairCheckInTheListAloneGrantsNothingInUnderwriting() throws Exception {
        UUID ana = newMember();

        mvc.perform(asMember(post("/api/v1/members/me/consents"), ana).content("{\"type\":\"FAIR_CHECK\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("FAIR_CHECK"));
        settle();

        assertThat(underwriting.getConsentStatus(ana).consented()).isFalse();
    }

    @Test
    void theFairCheckTextVersionIsChecked() throws Exception {
        UUID ana = newMember();

        mvc.perform(asMember(post("/api/v1/members/me/consents"), ana).content("{\"type\":\"FAIR_CHECK\",\"version\":\"2020-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("consent-version-stale"))
                .andExpect(jsonPath("$.details.currentVersion").value("2026-11"));
    }

    @Test
    void anotherMembersConsentListIsNeverTouched() throws Exception {
        UUID ana = newMember();
        UUID ben = newMember();
        giveFairCheckConsent(ana);
        giveFairCheckConsent(ben);

        mvc.perform(asMember(delete("/api/v1/members/me/consents/FAIR_CHECK"), ana)).andExpect(status().isNoContent());
        settle();

        assertThat(underwriting.getConsentStatus(ben).consented()).isTrue();
        assertThat(identity.hasActiveConsent(ben, ConsentType.FAIR_CHECK)).isTrue();
    }
}
