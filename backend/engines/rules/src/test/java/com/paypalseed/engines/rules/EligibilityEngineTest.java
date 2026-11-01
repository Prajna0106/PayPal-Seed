package com.paypalseed.engines.rules;

import com.paypalseed.engines.rules.EligibilityEngine.EligibilityEvaluation;
import com.paypalseed.engines.rules.EligibilityEngine.EligibilityInput;
import com.paypalseed.engines.rules.EligibilityEngine.SizingDecision;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EligibilityEngineTest {

    @Test
    void anaDemoScenario_fourMonthsAt75DollarsUnlocksLevel1AndApproves1200Over8Months() {
        // Ana saves $75/month for 4 months, verified income averages $1,600/month
        EligibilityInput ana = new EligibilityInput(
            4,
            4,
            0,
            7_500L, // $75.00
            OptionalLong.of(160_000L), // $1,600.00
            0,
            false
        );

        EligibilityEvaluation eval = EligibilityEngine.evaluate(ana);

        assertThat(eval.eligible()).isTrue();
        assertThat(eval.tier()).isEqualTo(LoanTier.LEVEL_1);
        assertThat(eval.tierLimitCents()).isEqualTo(150_000L); // $1,500
        // Cap is min(2 * $75 = $150, 15% * $1,600 = $240) = $150
        assertThat(eval.maxMonthlyInstalmentCents()).isEqualTo(15_000L);

        // Request $1,200 mixer over 8 months ($150/month)
        SizingDecision decision = EligibilityEngine.checkApplication(ana, 120_000L, 8);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.requiredMonthlyInstalmentCents()).isEqualTo(15_000L);
        assertThat(decision.suggestedPrincipalCents()).isEqualTo(120_000L);
        assertThat(decision.suggestedTermMonths()).isEqualTo(8);
    }

    @Test
    void incomeCap_bindsWhen15PercentOfIncomeIsSmallerThanTwiceSavings() {
        // Saves $200/month (2x = $400), but verified income is $1,200/month (15% = $180)
        EligibilityInput input = new EligibilityInput(
            3,
            3,
            0,
            20_000L, // $200.00
            OptionalLong.of(120_000L), // $1,200.00
            0,
            false
        );

        EligibilityEvaluation eval = EligibilityEngine.evaluate(input);

        assertThat(eval.eligible()).isTrue();
        assertThat(eval.maxMonthlyInstalmentCents()).isEqualTo(18_000L); // $180.00
    }

    @Test
    void loanLadder_progressesFromLevel1ToLevel2AndLevel3WithOnTimeRepayments() {
        EligibilityInput level1Input = new EligibilityInput(3, 3, 1, 15_000L, OptionalLong.empty(), 0, false);
        EligibilityInput level2Input = new EligibilityInput(6, 6, 0, 15_000L, OptionalLong.empty(), 1, false);
        EligibilityInput level3Input = new EligibilityInput(12, 12, 0, 20_000L, OptionalLong.empty(), 2, false);

        assertThat(EligibilityEngine.evaluate(level1Input).tier()).isEqualTo(LoanTier.LEVEL_1);
        assertThat(EligibilityEngine.evaluate(level1Input).tierLimitCents()).isEqualTo(150_000L);

        assertThat(EligibilityEngine.evaluate(level2Input).tier()).isEqualTo(LoanTier.LEVEL_2);
        assertThat(EligibilityEngine.evaluate(level2Input).tierLimitCents()).isEqualTo(300_000L);

        EligibilityEvaluation eval3 = EligibilityEngine.evaluate(level3Input);
        assertThat(eval3.tier()).isEqualTo(LoanTier.LEVEL_3);
        assertThat(eval3.tierLimitCents()).isEqualTo(500_000L);
        assertThat(eval3.minTermMonths()).isEqualTo(6);
        assertThat(eval3.maxTermMonths()).isEqualTo(18);
    }

    @Test
    void unqualified_whenStreakUnderThreeMonthsOrTooManyMisses() {
        EligibilityInput twoMonths = new EligibilityInput(2, 2, 0, 10_000L, OptionalLong.empty(), 0, false);
        EligibilityEvaluation evalShort = EligibilityEngine.evaluate(twoMonths);
        assertThat(evalShort.eligible()).isFalse();
        assertThat(evalShort.tier()).isEqualTo(LoanTier.UNQUALIFIED);

        EligibilityInput twoMisses = new EligibilityInput(4, 4, 2, 10_000L, OptionalLong.empty(), 0, false);
        EligibilityEvaluation evalMissed = EligibilityEngine.evaluate(twoMisses);
        assertThat(evalMissed.eligible()).isFalse();
        assertThat(evalMissed.tier()).isEqualTo(LoanTier.UNQUALIFIED);
    }

    @Test
    void activeLoan_blocksConcurrentApplicationAtLevel1AndLevel2() {
        EligibilityInput activeLoanInput = new EligibilityInput(5, 5, 0, 10_000L, OptionalLong.empty(), 1, true);
        EligibilityEvaluation eval = EligibilityEngine.evaluate(activeLoanInput);

        assertThat(eval.eligible()).isFalse();
        assertThat(eval.tier()).isEqualTo(LoanTier.LEVEL_2);
        assertThat(eval.reasons()).anyMatch(r -> r.contains("Only one active microloan"));
    }

    @Test
    void sizingBlock_suggestsLongerTermWhenMonthlyInstalmentExceedsCap() {
        // Ana ($150/month cap) requests $1,200 over 4 months ($300/month)
        EligibilityInput ana = new EligibilityInput(4, 4, 0, 7_500L, OptionalLong.of(160_000L), 0, false);

        SizingDecision decision = EligibilityEngine.checkApplication(ana, 120_000L, 4);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.requiredMonthlyInstalmentCents()).isEqualTo(30_000L); // $300
        assertThat(decision.maxMonthlyInstalmentCents()).isEqualTo(15_000L); // $150
        assertThat(decision.suggestedPrincipalCents()).isEqualTo(120_000L);
        assertThat(decision.suggestedTermMonths()).isEqualTo(8); // 8 months brings it to $150/month
    }

    @Test
    void sizingBlock_suggestsSafeAmountWhenRequestedPrincipalExceedsTierLimit() {
        // Ana requests $2,000 at Level 1 ($1,500 limit) over 10 months
        EligibilityInput ana = new EligibilityInput(4, 4, 0, 7_500L, OptionalLong.of(160_000L), 0, false);

        SizingDecision decision = EligibilityEngine.checkApplication(ana, 200_000L, 10);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.suggestedPrincipalCents()).isEqualTo(150_000L); // $1,500 max for Level 1
        assertThat(decision.suggestedTermMonths()).isEqualTo(10);
    }
}
