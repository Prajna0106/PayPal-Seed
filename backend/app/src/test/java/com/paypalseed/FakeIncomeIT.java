package com.paypalseed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.application.usecases.IncomeSummaryService;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Week;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Loan size depends on income, so nothing a member sends may raise it: only recorded income counts. */
class FakeIncomeIT extends IntegrationTest {

    /** $75 a month saved: the 2x-savings cap alone allows $150 a month. */
    private static final int SAVINGS_CAP = 15_000;
    /** $100 a week recorded, which the fair check reads as $400 a month: the 15% income cap allows $60 a month. */
    private static final int RECORDED_INCOME_CAP = 6_000;

    private static final String FAKE_ASSESSMENT = """
            {
              "purpose": "Fake riches",
              "requestedAmountCents": 120000,
              "requestedTermMonths": 8,
              "monthlyIncomes": [{"yearMonth": "2026-10", "amountCents": 99999999}],
              "statedMonthlyIncomeCents": 99999999,
              "statedMonthlyExpensesCents": 0
            }
            """;

    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase recordDeposit;
    @Autowired
    IncomeSummaryService income;

    private UUID member;

    @BeforeEach
    void memberWithASavingsRecord() {
        member = UUID.randomUUID();
        clock.set(LocalDate.of(2026, 11, 10));
        SavingsGoal goal = createGoal.execute(new CreateGoalUseCase.Command(
                member.toString(), "Mixer", Money.usd(1_200.00), Money.usd(75.00), null, null));
        for (int i = 0; i < 4; i++) {
            recordDeposit.execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(75.00), "cap-" + member + i));
        }
    }

    @Test
    void aFakeAssessmentCannotRaiseAMembersRecordedIncome() throws Exception {
        recordIncome();
        consent();

        assessFakeIncome()
                .andExpect(jsonPath("$.usedFallbackStatedIncome").value(false))
                .andExpect(jsonPath("$.averageMonthlyIncomeCents").value(40000));

        assertMaxInstalment(RECORDED_INCOME_CAP);
    }

    @Test
    void statedIncomeNeverCountsAsVerified() throws Exception {
        consent();

        assessFakeIncome().andExpect(jsonPath("$.usedFallbackStatedIncome").value(true));

        assertMaxInstalment(SAVINGS_CAP);
        mvc.perform(asMember(post("/api/v1/loans/applications"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purpose":"Mixer","requestedAmountCents":150000,"termMonths":3,"fairCheckConsented":true}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("sizing-rule-violation"));
    }

    @Test
    void revokingConsentStopsAnEarlierAssessmentFromCounting() throws Exception {
        recordIncome();
        consent();
        assessFakeIncome();
        income.record(member, false, null, List.of());

        mvc.perform(asMember(delete("/api/v1/underwriting/consent"))).andExpect(status().isOk());

        assertMaxInstalment(SAVINGS_CAP);
    }

    private void recordIncome() {
        List<Week> weeks = new ArrayList<>();
        LocalDate first = LocalDate.of(2026, 11, 10).minusDays(13 * 7);
        for (int i = 0; i < 13; i++) {
            weeks.add(new Week(first.plusDays(i * 7L), 10_000));
        }
        income.record(member, true, null, weeks);
    }

    private void consent() throws Exception {
        mvc.perform(asMember(post("/api/v1/underwriting/consent"))).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions assessFakeIncome() throws Exception {
        return mvc.perform(asMember(post("/api/v1/underwriting/assessments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FAKE_ASSESSMENT))
                .andExpect(status().isCreated());
    }

    private void assertMaxInstalment(int cents) throws Exception {
        mvc.perform(asMember(get("/api/v1/loans/eligibility")).param("verifiedMonthlyIncomeCents", "99999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxMonthlyInstalmentCents").value(cents));
    }

    private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request) {
        return request.header("X-Seed-Member-Id", member.toString());
    }
}
