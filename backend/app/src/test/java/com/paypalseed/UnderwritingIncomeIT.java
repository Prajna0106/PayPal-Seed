package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** The F3 income slice F5 reads: consented weekly totals bucketed into 13 weeks ending the day before. */
class UnderwritingIncomeIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final LocalDate FIRST_WEEK = TODAY.minusDays(91);

    @Autowired
    UnderwritingFacade underwriting;

    private ResultActions seed(UUID memberId, String json, String authorization) throws Exception {
        return mvc.perform(put("/internal/underwriting/members/{id}/income", memberId)
                .contentType(MediaType.APPLICATION_JSON).content(json).header("Authorization", authorization));
    }

    private static String weeks(List<Long> amounts, int extraFutureWeeks) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < amounts.size() + extraFutureWeeks; i++) {
            long cents = i < amounts.size() ? amounts.get(i) : 99_999;
            rows.add("{\"weekStart\":\"" + FIRST_WEEK.plusDays(7L * i) + "\",\"inflowCents\":" + cents + "}");
        }
        return String.join(",", rows);
    }

    @Test
    void consentedWeeksBecomeThirteenBucketsEndingYesterday() throws Exception {
        clock.set(TODAY);
        UUID member = UUID.randomUUID();
        List<Long> ana = new ArrayList<>(List.of(20_000L, 21_000L, 19_000L, 20_000L, 22_000L, 20_000L, 18_000L, 20_000L,
                21_000L, 9_000L, 10_000L, 8_000L, 11_000L));

        seed(member, "{\"consent\":true,\"usualPaydayDow\":5,\"weeks\":[" + weeks(ana, 1) + "]}", SERVICE_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyInflowCents.length()").value(13))
                .andExpect(jsonPath("$.weeklyInflowCents[12]").value(11_000))
                .andExpect(jsonPath("$.usualPaydayDow").value(5));

        IncomeSummary summary = underwriting.incomeSummary(member, TODAY).orElseThrow();
        assertThat(summary.weeklyInflowCents()).isEqualTo(ana); // the week starting today is not complete, so it is left out
        assertThat(summary.activeWeeks()).isEqualTo(13);
        assertThat(summary.weeks()).hasSize(13);

        // re-sending a week replaces its total
        seed(member, "{\"consent\":true,\"usualPaydayDow\":5,\"weeks\":[{\"weekStart\":\"" + FIRST_WEEK.plusDays(84)
                + "\",\"inflowCents\":0}]}", SERVICE_TOKEN).andExpect(status().isOk());
        IncomeSummary updated = underwriting.incomeSummary(member, TODAY).orElseThrow();
        assertThat(updated.weeklyInflowCents().get(12)).isZero();
        assertThat(updated.activeWeeks()).isEqualTo(12);

        // a week later the window moves by one bucket
        assertThat(underwriting.incomeSummary(member, TODAY.plusDays(7)).orElseThrow().weeklyInflowCents().get(11)).isZero();
    }

    @Test
    void noConsentMeansNoSummary() throws Exception {
        clock.set(TODAY);
        UUID member = UUID.randomUUID();
        assertThat(underwriting.incomeSummary(member, TODAY)).isEmpty();

        seed(member, "{\"consent\":true,\"weeks\":[" + weeks(List.of(5_000L, 5_000L), 0) + "]}", SERVICE_TOKEN)
                .andExpect(status().isOk());
        assertThat(underwriting.incomeSummary(member, TODAY)).isPresent();

        seed(member, "{\"consent\":false}", SERVICE_TOKEN).andExpect(status().isOk());
        assertThat(underwriting.incomeSummary(member, TODAY)).isEmpty();
    }

    @Test
    void onlyTheServiceMaySeedAndAmountsAreValidated() throws Exception {
        UUID member = UUID.randomUUID();
        mvc.perform(put("/internal/underwriting/members/{id}/income", member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consent\":true,\"weeks\":[]}").header("X-Seed-Member-Id", member.toString()))
                .andExpect(status().isForbidden());
        seed(member, "{\"consent\":true,\"weeks\":[{\"weekStart\":\"2026-10-01\",\"inflowCents\":-5}]}", SERVICE_TOKEN)
                .andExpect(status().is4xxClientError());
        assertThat(underwriting.incomeSummary(member, TODAY)).isEmpty();
    }
}
