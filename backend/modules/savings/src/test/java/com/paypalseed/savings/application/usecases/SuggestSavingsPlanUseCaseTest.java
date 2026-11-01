package com.paypalseed.savings.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.savings.api.SavingsCoachNarrator.CoachNarrationResult;
import com.paypalseed.savings.api.dto.CoachSuggestionRequest;
import com.paypalseed.savings.api.dto.CoachSuggestionResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SuggestSavingsPlanUseCaseTest {

    @Test
    void computesRealisticSavingAndLoanTermFromDisposableIncome() {
        SuggestSavingsPlanUseCase useCase = new SuggestSavingsPlanUseCase();

        CoachSuggestionResponse res = useCase.execute(new CoachSuggestionRequest(
            "member-maria",
            "Commercial Sewing Machine",
            new BigDecimal("1200.00"),
            new BigDecimal("1950.00"),
            new BigDecimal("1200.00"),
            "USD"
        ));

        // Disposable = $1,950 - $1,200 = $750 -> 10% = $75/month
        assertThat(res.disposableMonthlyIncome()).isEqualByComparingTo("750.00");
        assertThat(res.suggestedMonthlySaving()).isEqualByComparingTo("75.00");
        assertThat(res.streakMonthsToUnlock()).isEqualTo(4);
        assertThat(res.totalSavedAfterStreak()).isEqualByComparingTo("300.00");
        assertThat(res.unlockedTier()).isEqualTo("LEVEL_1");
        // Max instalment = min(2 * $75 = $150, 15% of $1,950 = $292.50) = $150/month
        assertThat(res.maxMonthlyInstalment()).isEqualByComparingTo("150.00");
        // $1,200 / $150 = 8 months
        assertThat(res.unlockedLoanAmount()).isEqualByComparingTo("1200.00");
        assertThat(res.suggestedLoanTermMonths()).isEqualTo(8);
        assertThat(res.suggestedMonthlyInstalment()).isEqualByComparingTo("150.00");
        assertThat(res.modelGenerated()).isFalse();
        assertThat(res.messageEn()).contains("$75").contains("$1,200").contains("$150").contains("8 months");
        assertThat(res.messageEs()).contains("$75").contains("$1,200").contains("$150").contains("8 meses");
    }

    @Test
    void usesNarratorOutputWhenGroundedNarratorIsWired() {
        SuggestSavingsPlanUseCase useCase = new SuggestSavingsPlanUseCase(
            input -> new CoachNarrationResult(
                "Coach EN: Save $75/mo for 4 months to unlock $1,200 at $150/mo for 8 months.",
                "Coach ES: Ahorra $75/mes por 4 meses para desbloquear $1,200 a $150/mes por 8 meses.",
                true
            )
        );

        CoachSuggestionResponse res = useCase.execute(new CoachSuggestionRequest(
            "member-maria",
            "Sewing Machine",
            new BigDecimal("1200.00"),
            new BigDecimal("1950.00"),
            new BigDecimal("1200.00"),
            "USD"
        ));

        assertThat(res.modelGenerated()).isTrue();
        assertThat(res.messageEn()).startsWith("Coach EN:");
        assertThat(res.messageEs()).startsWith("Coach ES:");
    }

    @Test
    void rejectsNonPositiveIncome() {
        SuggestSavingsPlanUseCase useCase = new SuggestSavingsPlanUseCase();
        assertThatThrownBy(() -> useCase.execute(new CoachSuggestionRequest(
            "m1", "Goal", new BigDecimal("500"), BigDecimal.ZERO, BigDecimal.ZERO, "USD"
        ))).isInstanceOf(IllegalArgumentException.class);
    }
}
