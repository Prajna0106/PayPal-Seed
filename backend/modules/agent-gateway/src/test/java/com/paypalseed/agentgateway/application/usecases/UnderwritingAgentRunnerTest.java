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
import com.paypalseed.underwriting.api.FairCheckNarrator.NarrationInput;
import com.paypalseed.underwriting.api.FairCheckNarrator.NarrationResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnderwritingAgentRunnerTest {

    private ModelGateway models;
    private AgentRuns runs;
    private KillSwitch killSwitch;
    private UnderwritingAgentRunner runner;
    private UUID runId;

    @BeforeEach
    void setUp() {
        models = mock(ModelGateway.class);
        runs = mock(AgentRuns.class);
        killSwitch = mock(KillSwitch.class);
        runner = new UnderwritingAgentRunner(models, runs, killSwitch, new ObjectMapper());
        runId = UUID.randomUUID();
        when(runs.start(eq(UnderwritingAgentRunner.AGENT), any(), any(), any(), eq("NARRATIVE"))).thenReturn(runId);
    }

    @Test
    void returnsGroundedModelExplanationInLiveMode() {
        when(killSwitch.mode(UnderwritingAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "plainLanguageReasons": [
                "Your average monthly income over 3 months is $1,950 (lowest month: $1,700).",
                "You saved $75 on time for 4 of 4 months, capping your safe monthly payment at $150.",
                "We recommend a $1,200 loan over 8 months at $150 per month."
              ],
              "officerQuestions": [
                "Could you share how the sewing machine will support your monthly income after the $1,700 August dip?"
              ]
            }
            """,
            List.of(), 120, 90, 500L, 250L, "stop"
        ));

        NarrationResult result = runner.narrate(sampleInput());

        assertThat(result.plainLanguageReasons()).hasSize(3);
        assertThat(result.plainLanguageReasons().get(0)).contains("$1,950");
        assertThat(result.officerQuestions()).hasSize(1);
        verify(runs).finish(runId, "PASSED", "MODEL", null);
        verify(killSwitch, never()).recordGroundingFailure(any());
    }

    @Test
    void rejectsHallucinatedDollarAmountAndFallsBackToTemplate() {
        when(killSwitch.mode(UnderwritingAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        // $9,999 is not in the computed input figures -> grounding check must reject it
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "plainLanguageReasons": [
                "Your average monthly income is $9,999.",
                "We recommend a $1,200 loan at $150 per month."
              ],
              "officerQuestions": [
                "How will you use the $1,200 loan?"
              ]
            }
            """,
            List.of(), 100, 80, 400L, 200L, "stop"
        ));

        NarrationInput input = sampleInput();
        NarrationResult result = runner.narrate(input);

        assertThat(result.plainLanguageReasons()).isEqualTo(input.fallbackReasons());
        assertThat(result.officerQuestions()).isEqualTo(input.fallbackOfficerQuestions());
        verify(killSwitch).recordGroundingFailure(UnderwritingAgentRunner.AGENT);
        verify(runs).finish(runId, "FAILED_FELL_BACK", "TEMPLATE_FALLBACK", "UNGROUNDED_OR_INVALID");
    }

    @Test
    void skipsModelCallWhenKillSwitchIsTemplatesOnly() {
        when(killSwitch.mode(UnderwritingAgentRunner.AGENT)).thenReturn(Mode.TEMPLATES_ONLY);

        NarrationInput input = sampleInput();
        NarrationResult result = runner.narrate(input);

        assertThat(result.plainLanguageReasons()).isEqualTo(input.fallbackReasons());
        verify(models, never()).complete(any());
        verify(runs).finish(runId, "NOT_RUN", "TEMPLATES_ONLY", "AGENT_DISABLED");
    }

    private static NarrationInput sampleInput() {
        return new NarrationInput(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Used commercial sewing machine",
            true,
            "LEVEL_1",
            false,
            195_000L,
            170_000L,
            "2026-08",
            0.09,
            false,
            7_500L,
            4,
            4,
            15_000L,
            120_000L,
            8,
            120_000L,
            8,
            15_000L,
            null,
            List.of("Fallback reason 1", "Fallback reason 2"),
            List.of("Fallback officer question 1")
        );
    }
}
