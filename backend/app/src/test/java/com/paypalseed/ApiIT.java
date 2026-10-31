package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.support.IntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** TC-API-*, TC-PAY-01/02/05: the HTTP contract, security and error model (RFC 9457). */
class ApiIT extends IntegrationTest {

    private LoanSummary loan;

    @BeforeEach
    void loan() {
        loan = disburse(UUID.randomUUID(), 1_200, 8, 9, LocalDate.of(2026, 11, 10));
        clock.set(LocalDate.of(2026, 11, 20));
    }

    private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request) {
        return request.header("X-Seed-Member-Id", loan.memberId().toString());
    }

    @Test
    void tcApi02_noIdentityIs401_anotherMembersLoanIs404() throws Exception {
        mvc.perform(get("/api/v1/loans/{id}/schedule", loan.loanId()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthenticated"));
        mvc.perform(get("/api/v1/loans/{id}/schedule", loan.loanId()).header("X-Seed-Member-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://problems.paypal-seed.dev/loan-not-found"));
    }

    @Test
    void tcApi01_scheduleSupportsConditionalGet() throws Exception {
        String etag = mvc.perform(asMember(get("/api/v1/loans/{id}/schedule", loan.loanId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instalments.length()").value(8))
                .andExpect(jsonPath("$.instalments[0].status").value("SCHEDULED"))
                .andExpect(header().exists("ETag"))
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(asMember(get("/api/v1/loans/{id}/schedule", loan.loanId())).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void tcPay01_tcPay02_paymentsAreWholeDollarsUpToTheBalance() throws Exception {
        mvc.perform(asMember(post("/api/v1/loans/{id}/payments", loan.loanId())).header("Idempotency-Key", "a")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":15050,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("amount-not-whole-dollars"));
        mvc.perform(asMember(post("/api/v1/loans/{id}/payments", loan.loanId())).header("Idempotency-Key", "b")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":200000,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("amount-exceeds-outstanding"))
                .andExpect(jsonPath("$.details.outstandingCents").value(120_000));
        mvc.perform(asMember(post("/api/v1/loans/{id}/payments", loan.loanId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":15000,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(asMember(post("/api/v1/loans/{id}/payments", loan.loanId())).header("Idempotency-Key", "c")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":-5,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation-failed"));
    }

    @Test
    void tcApi03_idempotencyKeyReplaysTheSameOrderAndRefusesADifferentRequest() throws Exception {
        String first = mvc.perform(asMember(post("/api/v1/loans/{id}/payments", loan.loanId())).header("Idempotency-Key", "same")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":15000,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(asMember(post("/api/v1/loans/{id}/payments", loan.loanId())).header("Idempotency-Key", "same")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":15000,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(paypal.callCount("createOrder")).isEqualTo(1);
        mvc.perform(asMember(post("/api/v1/loans/{id}/payments", loan.loanId())).header("Idempotency-Key", "same")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":30000,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency-key-reused"));
    }

    @Test
    void paypalOutageIs503AndTheSameKeySucceedsOnRetry() throws Exception {
        paypal.failNext("createOrder", com.paypalseed.support.FakePayPal.unavailable());
        mvc.perform(asMember(post("/api/v1/loans/{id}/payments", loan.loanId())).header("Idempotency-Key", "retry-me")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":15000,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("dependency-unavailable"));
        mvc.perform(asMember(post("/api/v1/loans/{id}/payments", loan.loanId())).header("Idempotency-Key", "retry-me")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":15000,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void tcApi04_internalJobsNeedTheServiceToken() throws Exception {
        mvc.perform(asMember(post("/internal/jobs/mandates/reconcile")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(post("/internal/jobs/mandates/reconcile").header("Authorization", "Bearer wrong-token-0123456789"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/jobs/mandates/reconcile").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/desk/loans/{id}/integrity", loan.loanId())
                        .header("X-Seed-Staff-Id", UUID.randomUUID().toString()).header("X-Seed-Staff-Roles", "officer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consistent").value(true));
        mvc.perform(get("/api/v1/desk/loans/{id}/integrity", loan.loanId())
                        .header("X-Seed-Staff-Id", UUID.randomUUID().toString()).header("X-Seed-Staff-Roles", "service"))
                .andExpect(status().isForbidden()); // a staff header can never grant the service role
    }

    @Test
    void tcApi10_unverifiableWebhooksAreRefusedAndNeverStored() throws Exception {
        mvc.perform(post("/api/v1/webhooks/paypal").contentType(MediaType.APPLICATION_JSON)
                        .header("paypal-transmission-sig", "bad")
                        .content("{\"id\":\"WH-FORGED\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{}}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("invalid-webhook"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payments.webhook_inbox WHERE paypal_event_id = 'WH-FORGED'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void tcPay05_previewsNeverWrite() throws Exception {
        long versions = jdbc.sql("SELECT COUNT(*) FROM lending.schedule_version").query(Long.class).single();
        long transactions = jdbc.sql("SELECT COUNT(*) FROM lending.loan_transaction").query(Long.class).single();
        mvc.perform(asMember(post("/api/v1/loans/{id}/payments/preview", loan.loanId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":20000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingCents").value(100_000))
                .andExpect(jsonPath("$.instalments[7].status").value("PREPAID"));
        mvc.perform(asMember(post("/api/v1/loans/{id}/schedule/preview", loan.loanId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"primitive\":\"REAMORTIZE\",\"extraMonths\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposed.instalments.length()").value(12))
                .andExpect(jsonPath("$.proposed.instalments[0].amountCents").value(10_000));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.schedule_version").query(Long.class).single()).isEqualTo(versions);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_transaction").query(Long.class).single()).isEqualTo(transactions);
    }

    @Test
    void tcApi09_timelineShowsThePreviousPlanAsGhostsAfterAPause() throws Exception {
        mvc.perform(post("/internal/loans/{id}/reschedules", loan.loanId()).header("Authorization", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primitive\":\"DEFER_TO_END\",\"seq\":1,\"approvalRef\":\"appr-ghost\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        settle();
        mvc.perform(asMember(get("/api/v1/loans/{id}/timeline", loan.loanId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(8))
                .andExpect(jsonPath("$.ghostEvents[0].startDate").value("2026-12-09"))
                .andExpect(jsonPath("$.ghostEvents[0].readOnly").value(true))
                .andExpect(jsonPath("$.dependencies[0].to").value("v2-s8"));
        mvc.perform(asMember(get("/api/v1/loans/{id}/schedule/versions", loan.loanId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].reason").value("HARDSHIP_PAUSE"))
                .andExpect(jsonPath("$[1].causedBy").value("approval:appr-ghost"));
    }

    @Test
    void reschedulingRulesAreReportedAsProblems() throws Exception {
        mvc.perform(post("/internal/loans/{id}/reschedules", loan.loanId()).header("Authorization", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primitive\":\"MOVE_DATE\",\"seq\":1,\"days\":40,\"approvalRef\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/loans/{id}/reschedules", loan.loanId()).header("Authorization", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primitive\":\"MOVE_DATE\",\"seq\":1,\"approvalRef\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("missing-days"));
    }

    @Test
    void tcDds04_dueDaySuggestionFallsBackToStatedPayday() throws Exception {
        mvc.perform(asMember(get("/api/v1/members/me/due-day-suggestion")).param("statedPayday", "12"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dueDay").value(13))
                .andExpect(jsonPath("$.basis").value("STATED_PAYDAY"));
    }
}
