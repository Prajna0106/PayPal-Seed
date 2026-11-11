package com.paypalseed.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engines.assessment.AssessmentEngine;
import com.paypalseed.engines.assessment.AssessmentEngine.AssessmentInput;
import com.paypalseed.engines.assessment.AssessmentEngine.FairCheckResult;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.FairCheckNarrator;
import com.paypalseed.underwriting.api.FairCheckNarrator.NarrationResult;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment;
import com.paypalseed.underwriting.infrastructure.persistence.UnderwritingRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The card says whether the underwriting agent wrote the wording, and never claims it when the templates stayed. */
class FairCheckNarrationFlagTest {

    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static FairLoanCheckAssessment newAssessment() {
        FairCheckResult result = AssessmentEngine.assess(new AssessmentInput(
            List.of(), 160_000L, 80_000L, 7_500L, 4, 4, 0, 0, false, 120_000L, 8, "Stand mixer"));
        return FairLoanCheckAssessment.create(MEMBER, null, "Stand mixer", result, Instant.parse("2026-11-12T10:00:00Z"));
    }

    @Test
    void flagIsFalseByDefault() {
        FairLoanCheckAssessment a = newAssessment();

        assertThat(a.isNarratedByModel()).isFalse();
        assertThat(FairLoanCheckService.toCardView(a).modelGenerated()).isFalse();
    }

    @Test
    void flagIsTrueOnlyAfterGroundedNarrationIsApplied() {
        FairLoanCheckAssessment a = newAssessment();

        a.withGroundedNarration(List.of("Your income looks steady."), List.of("Ask about the stand mixer."));

        assertThat(a.isNarratedByModel()).isTrue();
        assertThat(a.getResult().plainLanguageReasons()).containsExactly("Your income looks steady.");
        assertThat(FairLoanCheckService.toCardView(a).modelGenerated()).isTrue();
    }

    @Test
    void flagStaysFalseWhenNarrationIsEmptyOrNull() {
        FairLoanCheckAssessment a = newAssessment();
        List<String> templateReasons = a.getResult().plainLanguageReasons();

        a.withGroundedNarration(List.of(), List.of("Question"));
        a.withGroundedNarration(List.of("Reason"), List.of());
        a.withGroundedNarration(null, List.of("Question"));
        a.withGroundedNarration(List.of("Reason"), null);

        assertThat(a.isNarratedByModel()).isFalse();
        assertThat(a.getResult().plainLanguageReasons()).isEqualTo(templateReasons);
    }

    @Test
    void templateWordingKeepsTheFlag() {
        FairLoanCheckAssessment a = newAssessment();
        a.withGroundedNarration(List.of("Model reason"), List.of("Model question"));

        a.withTemplateWording(List.of("Model reason", "Member disputed the figures."), List.of("Model question"));

        assertThat(a.isNarratedByModel()).isTrue();
        assertThat(a.getResult().plainLanguageReasons()).hasSize(2);
    }

    private static FairCheckCardView runWith(Function<FairCheckNarrator.NarrationInput, NarrationResult> narrate) {
        SavingsFacade savings = new SavingsFacade() {
            @Override
            public void recordDepositBySubscription(String subscriptionId, Money amount, String payPalCaptureId) {}

            @Override
            public void pauseGoalBySubscription(String subscriptionId) {}

            @Override
            public MemberSavingsSummary getMemberSavingsSummary(String memberId) {
                return new MemberSavingsSummary(memberId, 4, 4, 0, 7_500L, 30_000L, true);
            }
        };
        FairLoanCheckService service = new FairLoanCheckService(new UnderwritingRepository(), savings, narrate::apply);
        service.grantConsent(MEMBER);
        return service.runFairLoanCheck(MEMBER, new RunFairCheckCommand(
            UUID.randomUUID(), "Stand mixer", 120_000L, 8,
            List.of(new IncomeMonthDto("2026-10", 160_000L)), 160_000L, 80_000L, 0, false));
    }

    @Test
    void serviceMarksCardModelGeneratedOnlyForModelText() {
        assertThat(runWith(in -> new NarrationResult(List.of("Agent reason"), List.of("Agent question"))).modelGenerated())
            .isTrue();
        // narrator unavailable
        assertThat(runWith(in -> null).modelGenerated()).isFalse();
        // narrator gave the fallback sentences straight back (kill switch, grounding failed)
        assertThat(runWith(in -> new NarrationResult(in.fallbackReasons(), in.fallbackOfficerQuestions())).modelGenerated())
            .isFalse();
        // narrator returned empty lists
        assertThat(runWith(in -> new NarrationResult(List.of(), List.of())).modelGenerated()).isFalse();
    }
}
