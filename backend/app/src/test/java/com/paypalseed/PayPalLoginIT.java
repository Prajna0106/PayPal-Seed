package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.api.PayPalLogin.PayPalIdentity;
import com.paypalseed.support.FakePayPalLogin;
import com.paypalseed.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The web app's server hands over PayPal's code and gets back a Seed session for the right member. */
class PayPalLoginIT extends IntegrationTest {

    @Autowired
    FakePayPalLogin login;
    @Autowired
    ObjectMapper json;

    @Test
    void firstLoginCreatesTheMemberAndTheSessionWorks() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        JsonNode session = logIn(subject, "Ana Diaz");

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + session.get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("MEMBER"))
                .andExpect(jsonPath("$.id").value(session.get("memberId").asText()))
                .andExpect(jsonPath("$.displayName").value("Ana Diaz"));
        Boolean verified = jdbc.sql("SELECT verified_account FROM identity.member WHERE paypal_subject = :s")
                .param("s", subject).query(Boolean.class).single();
        assertThat(verified).isTrue();
    }

    @Test
    void aReturningMemberKeepsTheirAccountAndGetsFreshDetails() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        JsonNode first = logIn(subject, "Ana Diaz");
        JsonNode second = logIn(subject, "Ana Diaz-Reyes");

        assertThat(second.get("memberId")).isEqualTo(first.get("memberId"));
        assertThat(second.get("token")).isNotEqualTo(first.get("token"));
        String name = jdbc.sql("SELECT display_name FROM identity.member WHERE paypal_subject = :s")
                .param("s", subject).query(String.class).single();
        assertThat(name).isEqualTo("Ana Diaz-Reyes");
    }

    @Test
    void differentPayPalUsersGetDifferentMembers() throws Exception {
        assertThat(logIn("sub-" + UUID.randomUUID(), "Ana").get("memberId"))
                .isNotEqualTo(logIn("sub-" + UUID.randomUUID(), "Bob").get("memberId"));
    }

    @Test
    void aSuspendedMemberCannotLogIn() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        logIn(subject, "Ana");
        jdbc.sql("UPDATE identity.member SET status = 'SUSPENDED' WHERE paypal_subject = :s").param("s", subject).update();

        String code = willLogIn(subject, "Ana");
        exchange(code, post("/internal/auth/paypal/exchange").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account-suspended"));
    }

    @Test
    void aBadOrReusedCodeIsRefused() throws Exception {
        String code = willLogIn("sub-" + UUID.randomUUID(), "Ana");
        MockHttpServletRequestBuilder asService = post("/internal/auth/paypal/exchange").header("Authorization", SERVICE_TOKEN);
        exchange(code, asService).andExpect(status().isOk());

        exchange(code, post("/internal/auth/paypal/exchange").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("paypal-login-rejected"));
    }

    @Test
    void onlyTheWebAppsServerCanExchange() throws Exception {
        exchange(willLogIn("sub-" + UUID.randomUUID(), "Ana"), post("/internal/auth/paypal/exchange"))
                .andExpect(status().isUnauthorized());
        exchange(willLogIn("sub-" + UUID.randomUUID(), "Ana"), post("/internal/auth/paypal/exchange")
                .header("X-Seed-Member-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    private JsonNode logIn(String subject, String name) throws Exception {
        String body = exchange(willLogIn(subject, name),
                post("/internal/auth/paypal/exchange").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String willLogIn(String subject, String name) {
        String code = "code-" + UUID.randomUUID();
        login.willLogIn(code, new PayPalIdentity(subject, "PAYER", "member@example.test", name, true));
        return code;
    }

    private ResultActions exchange(String code, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"%s","codeVerifier":"verifier","redirectUri":"http://127.0.0.1:3000/api/auth/paypal/callback"}
                """.formatted(code)));
    }
}
