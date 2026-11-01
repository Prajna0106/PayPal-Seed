package com.paypalseed.engines.rules;

import com.paypalseed.engines.rules.EligibilityEngine.EligibilityEvaluation;
import com.paypalseed.engines.rules.EligibilityEngine.EligibilityInput;
import com.paypalseed.engines.rules.EligibilityEngine.SizingDecision;
import java.util.OptionalLong;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

class RulesEnginePropertyTest {

    @Property
    void approvedApplicationsAlwaysRespectTierAndAffordabilityInvariants(
        @ForAll @IntRange(min = 0, max = 24) int streakMonths,
        @ForAll @IntRange(min = 0, max = 4) int missedInQuarter,
        @ForAll @LongRange(min = 500L, max = 50_000L) long monthlySavingCents,
        @ForAll @LongRange(min = 50_000L, max = 800_000L) long monthlyIncomeCents,
        @ForAll @IntRange(min = 0, max = 4) int completedLoans,
        @ForAll boolean hasActiveLoan,
        @ForAll @LongRange(min = 50L, max = 6_000L) long requestedDollars,
        @ForAll @IntRange(min = 1, max = 24) int requestedTermMonths
    ) {
        long requestedPrincipalCents = requestedDollars * 100L;
        EligibilityInput input = new EligibilityInput(
            streakMonths,
            streakMonths,
            missedInQuarter,
            monthlySavingCents,
            OptionalLong.of(monthlyIncomeCents),
            completedLoans,
            hasActiveLoan
        );

        EligibilityEvaluation eval = EligibilityEngine.evaluate(input);
        SizingDecision decision = EligibilityEngine.checkApplication(input, requestedPrincipalCents, requestedTermMonths);

        if (decision.allowed()) {
            assertThat(eval.eligible()).isTrue();
            assertThat(hasActiveLoan).isFalse();
            assertThat(streakMonths).isGreaterThanOrEqualTo(EligibilityEngine.MIN_SAVING_STREAK_MONTHS);
            assertThat(missedInQuarter).isLessThanOrEqualTo(EligibilityEngine.MAX_MISSED_DEPOSITS_PER_QUARTER);
            assertThat(requestedPrincipalCents).isLessThanOrEqualTo(eval.tier().maxLoanCents());
            assertThat(requestedTermMonths).isBetween(eval.tier().minTermMonths(), eval.tier().maxTermMonths());
            assertThat(decision.requiredMonthlyInstalmentCents()).isLessThanOrEqualTo(eval.maxMonthlyInstalmentCents());
            assertThat(eval.maxMonthlyInstalmentCents()).isLessThanOrEqualTo(monthlySavingCents * 2L);
            assertThat(eval.maxMonthlyInstalmentCents()).isLessThanOrEqualTo((monthlyIncomeCents * 15L) / 100L);
        }
    }
}
