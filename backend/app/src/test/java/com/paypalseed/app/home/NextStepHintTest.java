package com.paypalseed.app.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.NextStepView;
import org.junit.jupiter.api.Test;

class NextStepHintTest {

    private static NextStepView next(int months, int loans, boolean shareTooLow, boolean lateOpen) {
        return new NextStepView("STEADY", months, loans, 8_500, shareTooLow, lateOpen);
    }

    @Test
    void saysWhatIsMissingInPlainEnglish() {
        assertThat(NextStepHint.of(next(2, 1, false, false), "en"))
            .isEqualTo("To reach Steady: 2 more on-time saving months and 1 more loan repaid on plan.");
        assertThat(NextStepHint.of(next(1, 0, false, false), "en")).isEqualTo("To reach Steady: 1 more on-time saving month.");
        assertThat(NextStepHint.of(next(0, 2, false, false), "en")).isEqualTo("To reach Steady: 2 more loans repaid on plan.");
    }

    @Test
    void saysWhatIsMissingInSpanish() {
        assertThat(NextStepHint.of(next(3, 0, false, false), "es"))
            .isEqualTo("Para llegar a Steady: 3 meses más de ahorro puntual.");
        assertThat(NextStepHint.of(next(1, 1, false, false), "es"))
            .isEqualTo("Para llegar a Steady: 1 mes más de ahorro puntual y 1 préstamo más pagado según el plan.");
    }

    @Test
    void aLateOpenPaymentIsMetWithEncouragementAndNoCounts() {
        assertThat(NextStepHint.of(next(2, 1, false, true), "en"))
            .isEqualTo("Catch up on your open payment and you are back on the way to Steady.");
    }

    @Test
    void onlyTheOnTimeShareBeingLowKeepsTheMessageShortAndKind() {
        assertThat(NextStepHint.of(next(0, 0, true, false), "en")).isEqualTo("Keep paying on time to reach Steady.");
        assertThat(NextStepHint.of(next(0, 0, true, false), "es")).isEqualTo("Sigue pagando a tiempo para llegar a Steady.");
    }

    @Test
    void anUnknownLanguageFallsBackToEnglish() {
        assertThat(NextStepHint.of(next(1, 0, false, false), "fr")).startsWith("To reach");
    }
}
