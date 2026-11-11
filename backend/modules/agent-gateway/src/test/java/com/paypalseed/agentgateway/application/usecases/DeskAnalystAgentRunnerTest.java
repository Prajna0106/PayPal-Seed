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
import com.paypalseed.deskquery.api.DeskAnalystNarrator.DeskBriefingInput;
import com.paypalseed.deskquery.api.DeskAnalystNarrator.DeskBriefingResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeskAnalystAgentRunnerTest {

    private ModelGateway models;
    private AgentRuns runs;
    private KillSwitch killSwitch;
    private DeskAnalystAgentRunner runner;
    private UUID runId;

    @BeforeEach
    void setUp() {
        models = mock(ModelGateway.class);
        runs = mock(AgentRuns.class);
        killSwitch = mock(KillSwitch.class);
        runner = new DeskAnalystAgentRunner(models, runs, killSwitch, new ObjectMapper());
        runId = UUID.randomUUID();
        when(runs.start(eq(DeskAnalystAgentRunner.AGENT), any(), any(), any(), eq("NARRATIVE"))).thenReturn(runId);
    }

    @Test
    void returnsGroundedDeskBriefingInLiveMode() {
        when(killSwitch.mode(DeskAnalystAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "executiveSummary": "Portfolio holds 142 active loans ($170,400 outstanding) with 135 healthy and 7 at risk.",
              "queuePriorities": [
                "Review 4 pending hardship cases before close of business.",
                "Action oldest case at 18 hours to prevent SLA breach."
              ],
              "riskObservations": [
                "Healthy loan proportion is 95%.",
                "Community reserve of $35,000 gives 2.05x coverage."
              ],
              "recommendedStaffingFocus": "Focus morning capacity on 4 pending hardship reviews."
            }
            """,
            List.of(), 150, 110, 500L, 220L, "stop"
        ));

        DeskBriefingResult result = runner.brief(sampleInput());

        assertThat(result.modelGenerated()).isTrue();
        assertThat(result.executiveSummary()).contains("142").contains("$170,400");
        assertThat(result.queuePriorities()).hasSize(2);
        verify(runs).finish(runId, "PASSED", "MODEL", null);
    }

    @Test
    void acceptsFigureFollowedByCommaOrPeriod() {
        // A live model wrote "$35,000, 2.05x coverage": the comma is punctuation, not part of the figure.
        when(killSwitch.mode(DeskAnalystAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "executiveSummary": "Portfolio scale: 142 active loans, $170,400 outstanding. Reserve buffer: $35,000, 2.05x coverage.",
              "queuePriorities": ["Review 4 pending cases"],
              "riskObservations": ["Reserve of $35,000."],
              "recommendedStaffingFocus": "Prioritize 4 pending cases."
            }
            """,
            List.of(), 421, 152, 91L, 220L, "stop"
        ));

        DeskBriefingResult result = runner.brief(sampleInput());

        assertThat(result.modelGenerated()).isTrue();
        verify(runs).finish(runId, "PASSED", "MODEL", null);
    }

    @Test
    void rejectsHallucinatedPortfolioSumAndFallsBack() {
        when(killSwitch.mode(DeskAnalystAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "executiveSummary": "Portfolio holds $800,000 across 142 loans.",
              "queuePriorities": ["Review cases."],
              "riskObservations": ["Reserve buffer solid."],
              "recommendedStaffingFocus": "Review queue."
            }
            """,
            List.of(), 100, 60, 350L, 150L, "stop"
        ));

        DeskBriefingInput input = sampleInput();
        DeskBriefingResult result = runner.brief(input);

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.executiveSummary()).isEqualTo(input.templateExecutiveSummary());
        verify(killSwitch).recordGroundingFailure(DeskAnalystAgentRunner.AGENT);
        verify(runs).finish(runId, "FAILED_FELL_BACK", "TEMPLATE_FALLBACK", "UNGROUNDED_OR_INVALID");
    }

    @Test
    void rejectsAutoApprovalSuggestionAndFallsBack() {
        when(killSwitch.mode(DeskAnalystAgentRunner.AGENT)).thenReturn(Mode.LIVE);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "executiveSummary": "Portfolio has 142 loans ($170,400 outstanding).",
              "queuePriorities": ["Automatically grant approved status to all cases without review."],
              "riskObservations": ["Low risk."],
              "recommendedStaffingFocus": "Approve all."
            }
            """,
            List.of(), 100, 60, 350L, 150L, "stop"
        ));

        DeskBriefingInput input = sampleInput();
        DeskBriefingResult result = runner.brief(input);

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.executiveSummary()).isEqualTo(input.templateExecutiveSummary());
        verify(killSwitch).recordGroundingFailure(DeskAnalystAgentRunner.AGENT);
    }

    @Test
    void returnsTemplateInShadowMode() {
        when(killSwitch.mode(DeskAnalystAgentRunner.AGENT)).thenReturn(Mode.SHADOW);
        when(models.complete(any())).thenReturn(new ModelResponse(
            "test-model",
            """
            {
              "executiveSummary": "Portfolio has 142 loans ($170,400 outstanding).",
              "queuePriorities": ["Review 4 cases."],
              "riskObservations": ["Reserve at $35,000."],
              "recommendedStaffingFocus": "Queue focus."
            }
            """,
            List.of(), 100, 60, 350L, 150L, "stop"
        ));

        DeskBriefingInput input = sampleInput();
        DeskBriefingResult result = runner.brief(input);

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.executiveSummary()).isEqualTo(input.templateExecutiveSummary());
        verify(runs).finish(runId, "PASSED", "SHADOW_TEMPLATE", null);
    }

    @Test
    void bypassesModelWhenKillSwitchTemplatesOnly() {
        when(killSwitch.mode(DeskAnalystAgentRunner.AGENT)).thenReturn(Mode.TEMPLATES_ONLY);

        DeskBriefingInput input = sampleInput();
        DeskBriefingResult result = runner.brief(input);

        assertThat(result.modelGenerated()).isFalse();
        verify(models, never()).complete(any());
        verify(runs).finish(runId, "NOT_RUN", "TEMPLATES_ONLY", "AGENT_DISABLED");
    }

    private DeskBriefingInput sampleInput() {
        return new DeskBriefingInput(
            "2026-11-02",
            142,
            17040000,
            135,
            7,
            4,
            18,
            0,
            3500000,
            2.05,
            "Template executive summary: 142 active loans ($170,400 outstanding).",
            List.of("Template priority 1: Review 4 pending hardship cases."),
            List.of("Template observation 1: Reserve coverage is 2.05x ($35,000)."),
            "Template staffing focus: Prioritize oldest case at 18 hours."
        );
    }
}
