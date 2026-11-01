package com.paypalseed.engines.assessment;

import com.paypalseed.engines.assessment.AssessmentEngine.AssessmentInput;
import com.paypalseed.engines.assessment.AssessmentEngine.FairCheckResult;
import com.paypalseed.engines.assessment.AssessmentEngine.MonthlyIncomePoint;
import com.paypalseed.engines.rules.LoanTier;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssessmentEngineTest {

    @Test
    void anaDemoCard_reproducesExactBlueprintNumbersAndPlainLanguageReasons() {
        // 6 months averaging $1,600 ($160,000 cents), with lowest month $1,180 ($118,000 cents)
        List<MonthlyIncomePoint> sixMonths = List.of(
            new MonthlyIncomePoint("2026-05", 165_000L),
            new MonthlyIncomePoint("2026-06", 172_000L),
            new MonthlyIncomePoint("2026-07", 118_000L), // lowest month: $1,180
            new MonthlyIncomePoint("2026-08", 165_000L),
            new MonthlyIncomePoint("2026-09", 170_000L),
            new MonthlyIncomePoint("2026-10", 170_000L)
        );

        AssessmentInput anaInput = new AssessmentInput(
            sixMonths,
            160_000L,
            90_000L,
            7_500L, // $75/month saving
            4,
            4,
            0,
            0,
            false,
            120_000L, // $1,200 mixer
            8,
            "Commercial stand mixer for home bakery"
        );

        FairCheckResult card = AssessmentEngine.assess(anaInput);

        assertThat(card.recommended()).isTrue();
        assertThat(card.unlockedTier()).isEqualTo(LoanTier.LEVEL_1);
        assertThat(card.averageMonthlyIncomeCents()).isEqualTo(160_000L); // $1,600
        assertThat(card.lowestMonthlyIncomeCents()).isEqualTo(118_000L); // $1,180
        assertThat(card.lowestIncomeMonth()).isEqualTo("2026-07");
        assertThat(card.highIncomeVolatility()).isFalse();
        assertThat(card.instalmentCapCents()).isEqualTo(15_000L); // $150
        assertThat(card.suggestedLoanCents()).isEqualTo(120_000L); // $1,200
        assertThat(card.suggestedTermMonths()).isEqualTo(8);
        assertThat(card.suggestedMonthlyInstalmentCents()).isEqualTo(15_000L); // $150/month
        assertThat(card.officerQuestions()).isEmpty();
        assertThat(card.plainLanguageReasons()).anyMatch(r -> r.contains("saved $75.00 every month for 4 of 4 months"));
        assertThat(card.usedData()).isNotEmpty();
        assertThat(card.neverUsedData()).anyMatch(d -> d.contains("Race, ethnicity"));
        assertThat(card.inputsHash()).hasSize(64);
    }

    @Test
    void volatilityGuard_suggestsLongerTermAndAddsSeasonalOfficerQuestionWhenIncomeSwings() {
        List<MonthlyIncomePoint> volatileIncomes = List.of(
            new MonthlyIncomePoint("2026-03", 60_000L), // sharp dip in March ($600)
            new MonthlyIncomePoint("2026-04", 200_000L),
            new MonthlyIncomePoint("2026-05", 220_000L),
            new MonthlyIncomePoint("2026-06", 180_000L),
            new MonthlyIncomePoint("2026-07", 240_000L),
            new MonthlyIncomePoint("2026-08", 180_000L)
        );

        AssessmentInput input = new AssessmentInput(
            volatileIncomes,
            180_000L,
            80_000L,
            10_000L,
            4,
            4,
            0,
            0,
            false,
            100_000L,
            6,
            "Delivery bicycle"
        );

        FairCheckResult card = AssessmentEngine.assess(input);

        assertThat(card.highIncomeVolatility()).isTrue();
        assertThat(card.suggestedTermMonths()).isEqualTo(8); // extended from 6 to 8 months by volatility guard
        assertThat(card.officerQuestions()).anyMatch(q -> q.contains("2026-03") && q.contains("seasonal"));
    }

    @Test
    void noPayPalHistory_fallsBackToStatedIncomeWith500DollarFirstLoanCap() {
        AssessmentInput fallbackInput = new AssessmentInput(
            List.of(), // no PayPal/Venmo history
            150_000L, // stated $1,500/month
            70_000L,
            10_000L, // $100/month saving
            4,
            4,
            0,
            0,
            false,
            120_000L, // requests $1,200
            6,
            "Sewing machine"
        );

        FairCheckResult card = AssessmentEngine.assess(fallbackInput);

        assertThat(card.usedFallbackStatedIncome()).isTrue();
        assertThat(card.suggestedLoanCents()).isEqualTo(50_000L); // capped at $500.00
        assertThat(card.officerQuestions()).anyMatch(q -> q.contains("verify stated income"));
    }
}
