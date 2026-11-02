package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * A fresh clone starts with the {@code dev} and {@code demo} profiles and no environment variable beyond the database.
 * This boots the whole application that way (pollers on, as in the worker role) and shows the settings that
 * {@code application.yml} and {@code application-dev.yml} declare reach the code that reads them. The other
 * integration tests run under the {@code test} profile, which hides a missing default.
 */
@ActiveProfiles(profiles = {"demo", "dev"}, inheritProfiles = false)
@TestPropertySource(properties = {
    "spring.datasource.username=postgres",
    "spring.datasource.password=postgres",
    "spring.modulith.events.republish-outstanding-events-on-restart=false"
})
class DevDemoProfileBootIT extends IntegrationTest {

    @Autowired
    Environment environment;

    @Test
    void startsWithOnlyTheDatabaseConfigured() {
        assertThat(environment.getActiveProfiles()).containsExactlyInAnyOrder("demo", "dev");
        assertThat(environment.getProperty("seed.jobs.enabled")).as("pollers follow the role, not a missing value").isNull();
        assertThat(environment.getProperty("seed.clock.mode")).isEqualTo("database");
        assertThat(environment.getProperty("seed.security.dev-header-auth", Boolean.class)).isTrue();
    }

    @Test
    void declaredDefaultsAreTheOnesTheCodeReads() {
        assertThat(environment.getProperty("seed.identity.consent.terms-version")).isEqualTo("2026-11");
        assertThat(environment.getProperty("seed.paypal.transaction-search.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("seed.paypal.transaction-search.on-behalf", Boolean.class)).isFalse();
        assertThat(environment.getProperty("seed.paypal.read-timeout-ms", Integer.class)).isEqualTo(20_000);
        assertThat(environment.getProperty("seed.savings.approval-window")).isEqualTo("PT24H");
        assertThat(environment.getProperty("seed.underwriting.income.source")).isEqualTo("SEEDED");
        assertThat(environment.getProperty("seed.reserve.cover.max-per-request-cents", Long.class)).isEqualTo(50_000L);
        assertThat(environment.getProperty("seed.fraud.flag-threshold", Integer.class)).isEqualTo(40);
        assertThat(environment.getProperty("seed.jobs.record-close-ms", Long.class)).isEqualTo(21_600_000L);
        assertThat(environment.getProperty("seed.security.actor-token.secrets")).startsWith("k1:");
        assertThat(environment.getProperty("seed.security.actor-token.clock-skew-seconds", Integer.class)).isEqualTo(30);
    }

    @Test
    void memberDeskAndPublicRoutesAnswerWithDevHeaders() throws Exception {
        UUID member = UUID.randomUUID();
        mvc.perform(get("/api/v1/members/me/record").header("X-Seed-Member-Id", member.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.level").value("NEW"));
        mvc.perform(get("/api/v1/desk/applications")
                .header("X-Seed-Staff-Id", UUID.randomUUID().toString()).header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/reserve/summary")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/records/verify/not-a-token")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/demo-clock/advance").param("days", "1")
                .header("Authorization", "Bearer local-dev-internal-token"))
            .andExpect(status().isOk());
    }
}
