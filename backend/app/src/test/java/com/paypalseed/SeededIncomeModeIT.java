package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The default income source is SEEDED: even with Transaction Search switched on in payments, the fair check never
 * reads PayPal, and the request's own monthly figures and the seeded weeks work exactly as before (ADR 0018).
 */
@TestPropertySource(properties = "seed.paypal.transaction-search.enabled=true")
class SeededIncomeModeIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Autowired
    UnderwritingFacade underwriting;

    @Test
    void theSeededPathKeepsWorkingAndPayPalIsNeverRead() throws Exception {
        clock.set(TODAY);
        UUID member = UUID.randomUUID();
        mvc.perform(put("/internal/underwriting/members/{id}/income", member).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", SERVICE_TOKEN)
                        .content("{\"consent\":true,\"usualPaydayDow\":5,\"weeks\":[{\"weekStart\":\""
                                + TODAY.minusDays(91) + "\",\"inflowCents\":12345}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyInflowCents[0]").value(12345));

        mvc.perform(post("/api/v1/underwriting/assessments").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Seed-Member-Id", member.toString())
                        .content("""
                                {"purpose":"Commercial stand mixer","requestedAmountCents":120000,"requestedTermMonths":8,
                                 "monthlyIncomes":[{"yearMonth":"2026-10","amountCents":160000}],
                                 "statedMonthlyIncomeCents":160000,"statedMonthlyExpensesCents":90000,
                                 "completedLoansOnTime":0,"hasActiveLoan":false}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.averageMonthlyIncomeCents").value(160_000));

        assertThat(paypal.searches).isEmpty();
        assertThat(paypal.calls).noneMatch(call -> call.startsWith("searchTransactions"));
        // The seeded weeks are untouched by the assessment.
        assertThat(underwriting.incomeSummary(member, TODAY).orElseThrow().weeklyInflowCents().get(0)).isEqualTo(12_345L);
    }

    @Test
    void aMemberWithNoIdentityRecordIsNotAskedToLinkAnAccountWhenIncomeIsSeeded() throws Exception {
        UUID member = UUID.randomUUID();
        underwriting.grantConsent(member);

        mvc.perform(post("/api/v1/underwriting/assessments").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Seed-Member-Id", member.toString())
                        .content("""
                                {"purpose":"Commercial stand mixer","requestedAmountCents":120000,"requestedTermMonths":8,
                                 "monthlyIncomes":[{"yearMonth":"2026-10","amountCents":160000}],
                                 "statedMonthlyIncomeCents":160000,"statedMonthlyExpensesCents":90000,
                                 "completedLoansOnTime":0,"hasActiveLoan":false}"""))
                .andExpect(status().isCreated());

        assertThat(paypal.searches).isEmpty();
    }
}
