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
import com.paypalseed.creditrecord.api.TrustRecordNarrator.TrustNarrationInput;
import com.paypalseed.creditrecord.api.TrustRecordNarrator.TrustNarrationResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustAgentRunnerTest {

    private ModelGateway models;
    private AgentRuns runs;
    private KillSwitch killSwitch;
    private TrustAgentRunner runner;
    private UUID runId;

    @BeforeEach
    void setUp() {
        models = mock(ModelGateway.class);
        runs = mock(AgentRuns.class);
        killSwitch = mock(KillSwitch.class);
        runner = new TrustAgentRunner(models, runs, killSwitch, new ObjectMapper());
        runId = UUID.randomUUID();
        when(runs.start(eq(TrustAgentRunner.AGENT), any(), any(), any(), eq("NARRATIVE"))).thenReturn(runId);
    }

    @Test
    void returnsGroundedTrustNarrationInLiveMode() {
        when(killSwitch.mode(TrustAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "summaryEn": "You are currently at Level 1 with 8 on-time repayments and $300 saved. Level 2 unlocks a $2,400 loan.",
              "summaryEs": "Estás en el Nivel 1 con 8 pagos puntuales y $300 ahorrados. El Nivel 2 desbloquea $2,400.",
              "certificateAttestation": "Member has successfully repaid $1,200 across 8 on-time installments.",
              "milestoneAdvice": "Complete your current loan cycle to reach Level 2."
            }
            """,
            List.of(), 120, 90, 420L, 190L, "stop"
        ));

        TrustNarrationResult result = runner.narrate(sampleInput());

        assertThat(result.modelGenerated()).isTrue();
        assertThat(result.summaryEn()).contains("Level 1").contains("$2,400");
        assertThat(result.certificateAttestation()).contains("$1,200");
        verify(runs).finish(runId, "PASSED", "MODEL", null);
    }

    @Test
    void rejectsHallucinatedLimitAndFallsBackToTemplate() {
        when(killSwitch.mode(TrustAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "summaryEn": "You have unlocked a $10,000 credit limit immediately!",
              "summaryEs": "Has desbloqueado $10,000.",
              "certificateAttestation": "Repaid $1,200.",
              "milestoneAdvice": "Enjoy $10,000."
            }
            """,
            List.of(), 100, 60, 350L, 150L, "stop"
        ));

        TrustNarrationInput input = sampleInput();
        TrustNarrationResult result = runner.narrate(input);

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.summaryEn()).isEqualTo(input.templateSummaryEn());
        verify(killSwitch).recordGroundingFailure(TrustAgentRunner.AGENT);
        verify(runs).finish(runId, "FAILED_FELL_BACK", "TEMPLATE_FALLBACK", "UNGROUNDED_OR_INVALID");
    }

    @Test
    void rejectsCreditBureauClaimAndFallsBack() {
        when(killSwitch.mode(TrustAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "summaryEn": "Level 1 standing with 8 on-time payments.",
              "summaryEs": "Nivel 1.",
              "certificateAttestation": "Repaid $1,200, boosting credit score at Experian credit bureau.",
              "milestoneAdvice": "Reach Level 2."
            }
            """,
            List.of(), 100, 60, 350L, 150L, "stop"
        ));

        TrustNarrationInput input = sampleInput();
        TrustNarrationResult result = runner.narrate(input);

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.certificateAttestation()).isEqualTo(input.templateAttestation());
        verify(killSwitch).recordGroundingFailure(TrustAgentRunner.AGENT);
    }

    @Test
    void returnsTemplateInShadowMode() {
        when(killSwitch.mode(TrustAgentRunner.AGENT)).thenReturn(Mode.SHADOW);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "summaryEn": "Level 1 member with 8 on-time repayments.",
              "summaryEs": "Nivel 1.",
              "certificateAttestation": "Repaid $1,200.",
              "milestoneAdvice": "Next tier unlocks $2,400."
            }
            """,
            List.of(), 100, 60, 350L, 150L, "stop"
        ));

        TrustNarrationInput input = sampleInput();
        TrustNarrationResult result = runner.narrate(input);

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.summaryEn()).isEqualTo(input.templateSummaryEn());
        verify(runs).finish(runId, "PASSED", "SHADOW_TEMPLATE", null);
    }

    @Test
    void bypassesModelWhenKillSwitchTemplatesOnly() {
        when(killSwitch.mode(TrustAgentRunner.AGENT)).thenReturn(Mode.TEMPLATES_ONLY);

        TrustNarrationInput input = sampleInput();
        TrustNarrationResult result = runner.narrate(input);

        assertThat(result.modelGenerated()).isFalse();
        verify(models, never()).complete(any());
        verify(runs).finish(runId, "NOT_RUN", "TEMPLATES_ONLY", "AGENT_DISABLED");
    }

    private TrustNarrationInput sampleInput() {
        return new TrustNarrationInput(
            "11111111-1111-1111-1111-111111111111",
            "LEVEL_1",
            "LEVEL_2",
            120000,
            240000,
            8,
            4,
            30000,
            120000,
            "Template summary EN: Level 1 with 8 on-time payments.",
            "Template summary ES: Nivel 1 con 8 pagos puntuales.",
            "Template attestation: Verified $1,200 repaid on time.",
            "Template advice: Maintain streak to unlock Level 2 ($2,400)."
        );
    }
}
