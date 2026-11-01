package com.paypalseed.agentgateway.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.KillSwitch.Mode;
import com.paypalseed.agentgateway.api.ModelGateway;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.savings.api.SavingsCoachNarrator.CoachNarrationInput;
import com.paypalseed.savings.api.SavingsCoachNarrator.CoachNarrationResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SavingsCoachAgentRunnerTest {

    private ModelGateway models;
    private AgentRuns runs;
    private KillSwitch killSwitch;
    private SavingsCoachAgentRunner runner;
    private UUID runId;

    @BeforeEach
    void setUp() {
        models = mock(ModelGateway.class);
        runs = mock(AgentRuns.class);
        killSwitch = mock(KillSwitch.class);
        runner = new SavingsCoachAgentRunner(models, runs, killSwitch, new ObjectMapper());
        runId = UUID.randomUUID();
        when(runs.start(eq(SavingsCoachAgentRunner.AGENT), any(), any(), any(), eq("NARRATIVE"))).thenReturn(runId);
    }

    @Test
    void returnsGroundedBilingualCoachingInLiveMode() {
        when(killSwitch.mode(SavingsCoachAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "messageEn": "Setting aside $75 a month for 4 months builds $300 in savings and unlocks a $1,200 zero-interest loan repaid at $150 a month over 8 months.",
              "messageEs": "Ahorrar $75 al mes por 4 meses suma $300 y desbloquea un préstamo sin intereses de $1,200 con pagos de $150 al mes por 8 meses."
            }
            """,
            List.of(), 100, 80, 400L, 180L, "stop"
        ));

        CoachNarrationResult result = runner.narrate(sampleInput());

        assertThat(result.modelGenerated()).isTrue();
        assertThat(result.messageEn()).contains("$75").contains("$1,200");
        assertThat(result.messageEs()).contains("$75").contains("$1,200");
        verify(runs).finish(runId, "PASSED", "MODEL", null);
    }

    @Test
    void rejectsForbiddenCreditScoreTermAndFallsBackToTemplate() {
        when(killSwitch.mode(SavingsCoachAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "messageEn": "Save $75 a month to unlock a $1,200 loan and boost your FICO credit score!",
              "messageEs": "Ahorra $75 al mes para un préstamo de $1,200."
            }
            """,
            List.of(), 100, 60, 350L, 150L, "stop"
        ));

        CoachNarrationInput input = sampleInput();
        CoachNarrationResult result = runner.narrate(input);

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.messageEn()).isEqualTo(input.templateMessageEn());
        verify(killSwitch).recordGroundingFailure(SavingsCoachAgentRunner.AGENT);
        verify(runs).finish(runId, "FAILED_FELL_BACK", "TEMPLATE_FALLBACK", "UNGROUNDED_OR_INVALID");
    }

    @Test
    void returnsTemplateInShadowModeAfterValidatingModelOutput() {
        when(killSwitch.mode(SavingsCoachAgentRunner.AGENT)).thenReturn(Mode.SHADOW);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "messageEn": "Save $75 a month for 4 months ($300 total) to unlock a $1,200 loan at $150 a month over 8 months.",
              "messageEs": "Ahorra $75 al mes por 4 meses ($300 en total) para desbloquear $1,200 a $150 al mes por 8 meses."
            }
            """,
            List.of(), 100, 80, 400L, 180L, "stop"
        ));

        CoachNarrationInput input = sampleInput();
        CoachNarrationResult result = runner.narrate(input);

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.messageEn()).isEqualTo(input.templateMessageEn());
        verify(runs).finish(runId, "PASSED", "SHADOW_TEMPLATE", null);
        verify(killSwitch, never()).recordGroundingFailure(any());
    }

    private static CoachNarrationInput sampleInput() {
        return new CoachNarrationInput(
            "member-maria",
            "Sewing Machine",
            120_000L,
            195_000L,
            120_000L,
            75_000L,
            7_500L,
            4,
            30_000L,
            "LEVEL_1",
            120_000L,
            15_000L,
            8,
            15_000L,
            "Template EN: $75/mo -> $1,200 loan",
            "Template ES: $75/mes -> $1,200 préstamo"
        );
    }
}
