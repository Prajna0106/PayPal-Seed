package com.paypalseed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** The public routes answer 429 as a problem once one address passes its allowance; signed-in routes are untouched. */
@TestPropertySource(properties = {
    "seed.security.public-rate-limit.enabled=true",
    "seed.security.public-rate-limit.requests-per-minute=3",
    "seed.security.public-rate-limit.trusted-proxy-hops=1"})
class PublicRateLimitIT extends IntegrationTest {

    @Test
    void publicRoutesShareAnAllowancePerAddressAndRefuseWithAProblem() throws Exception {
        String client = "198.51.100.20";
        mvc.perform(get("/api/v1/reserve/summary").header("X-Forwarded-For", client)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/records/verify/not-a-token").header("X-Forwarded-For", client))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/reserve/donations/intent").header("X-Forwarded-For", client)
                .header("Idempotency-Key", "rate-limit-it-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"anonymous\":true}"))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/reserve/summary").header("X-Forwarded-For", client))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("rate-limited"))
            .andExpect(jsonPath("$.type").value("https://problems.paypal-seed.dev/rate-limited"));

        mvc.perform(get("/api/v1/reserve/summary").header("X-Forwarded-For", "198.51.100.21"))
            .andExpect(status().isOk());
    }
}
