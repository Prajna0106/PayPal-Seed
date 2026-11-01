package com.paypalseed.engines.assessment;

import com.paypalseed.engines.assessment.AssessmentEngine.AssessmentInput;
import com.paypalseed.engines.assessment.AssessmentEngine.FairCheckResult;
import com.paypalseed.engines.assessment.AssessmentEngine.MonthlyIncomePoint;
import com.paypalseed.engines.rules.LoanTier;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

class AssessmentEnginePropertyTest {

    @Property
    void fairCheckIsDeterministicAndNeverExceedsInstalmentOrFallbackCaps(
        @ForAll @LongRange(min = 50_000L, max = 500_000L) long m1,
        @ForAll @LongRange(min = 50_000L, max = 500_000L) long m2,
        @ForAll @LongRange(min = 50_000L, max = 500_000L) long m3,
        @ForAll @LongRange(min = 500L, max = 40_000L) long savingCents,
        @ForAll @IntRange(min = 0, max = 12) int monthsSaved,
        @ForAll @IntRange(min = 0, max = 3) int misses,
        @ForAll boolean useHistory,
        @ForAll @LongRange(min = 100L, max = 4_000L) long requestedDollars,
        @ForAll @IntRange(min = 3, max = 12) int requestedTerm
    ) {
        List<MonthlyIncomePoint> history = useHistory
            ? List.of(
                new MonthlyIncomePoint("2026-08", m1),
                new MonthlyIncomePoint("2026-09", m2),
                new MonthlyIncomePoint("2026-10", m3)
            )
            : List.of();

        AssessmentInput input = new AssessmentInput(
            history,
            m1,
            30_000L,
            savingCents,
            monthsSaved,
            Math.max(monthsSaved, 4),
            misses,
            0,
            false,
            requestedDollars * 100L,
            requestedTerm,
            "Working capital"
        );

        FairCheckResult first = AssessmentEngine.assess(input);
        FairCheckResult second = AssessmentEngine.assess(input);

        // Determinism invariant
        assertThat(first.inputsHash()).isEqualTo(second.inputsHash());
        assertThat(first.suggestedLoanCents()).isEqualTo(second.suggestedLoanCents());
        assertThat(first.suggestedMonthlyInstalmentCents()).isEqualTo(second.suggestedMonthlyInstalmentCents());

        if (first.recommended() && first.suggestedLoanCents() > 0) {
            assertThat(first.suggestedMonthlyInstalmentCents()).isLessThanOrEqualTo(first.instalmentCapCents());
            if (!useHistory && first.unlockedTier() == LoanTier.LEVEL_1) {
                assertThat(first.suggestedLoanCents()).isLessThanOrEqualTo(AssessmentEngine.FALLBACK_FIRST_LOAN_CAP_CENTS);
            }
        }
    }
}
