package com.paypalseed.agentgateway.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.ProposalIntake.Receipt;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.Check;
import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.SubmitProposal;
import com.paypalseed.hardship.api.HardshipFacade;
import com.paypalseed.hardship.api.dto.HardshipDtos.AgentOutcome;
import com.paypalseed.platform.kernel.DomainException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProposalIntakeServiceTest {

    private static final UUID RUN = UUID.fromString("0197f3a9-0000-7000-8000-00000000a001");
    private static final UUID LOAN = UUID.fromString("0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e01");
    private static final UUID CASE = UUID.fromString("0197f3a8-1111-7000-8000-00000000c001");
    private static final UUID PROPOSAL = UUID.fromString("0197f3a9-2222-7000-8000-00000000d001");

    private AgentRuns runs;
    private KillSwitch killSwitch;
    private GovernanceFacade governance;
    private HardshipFacade hardship;
    private ProposalIntakeService intake;
    private AgentPrincipal principal;

    @BeforeEach
    void setUp() {
        AgentTokens tokens = mock(AgentTokens.class);
        runs = mock(AgentRuns.class);
        AgentGuard guard = mock(AgentGuard.class);
        killSwitch = mock(KillSwitch.class);
        governance = mock(GovernanceFacade.class);
        hardship = mock(HardshipFacade.class);
        intake = new ProposalIntakeService(tokens, runs, guard, killSwitch, governance, hardship, new ObjectMapper());
        principal = new AgentPrincipal(RUN, LOAN, CASE, AgentTokens.ALL_SCOPES);
        when(guard.requireAgentsOn(any())).thenAnswer(i -> i.getArgument(0));
        when(runs.agentOf(RUN)).thenReturn("guardian");
        when(killSwitch.mode("guardian")).thenReturn(KillSwitch.Mode.LIVE);
    }

    private static String proposal(UUID caseId) {
        return """
                {"runId":"%s","caseId":"%s","loanId":"%s","assessment":{"proposedLevel":"LIKELY_MISS","citedSignals":["LOW_COVERAGE"]},
                 "narrative":{"officerBrief":"brief","memberMessage":{"en":"hi","es":"hola"}}}""".formatted(RUN, caseId, LOAN);
    }

    private void verdict(boolean approvable, boolean replay, Check... checks) {
        when(governance.submitProposal(any())).thenReturn(new ProposalResult(PROPOSAL, approvable, List.of(checks), replay));
    }

    @Test
    void anApprovableProposalReachesHardshipAndFeedsTheKillSwitch() {
        verdict(true, false, new Check("V06_UNGROUNDED_FIGURE", true, "ok"));

        Receipt receipt = intake.submit(principal, RUN.toString(), proposal(CASE));

        assertThat(receipt.status()).isEqualTo("QUEUED_FOR_MEMBER");
        assertThat(receipt.templateFallback()).isFalse();
        ArgumentCaptor<SubmitProposal> submitted = ArgumentCaptor.forClass(SubmitProposal.class);
        verify(governance).submitProposal(submitted.capture());
        assertThat(submitted.getValue().kind()).isEqualTo("HARDSHIP_OUTREACH");
        assertThat(submitted.getValue().subjectId()).isEqualTo(CASE);
        assertThat(submitted.getValue().submittedBy()).isEqualTo("run:" + RUN);
        ArgumentCaptor<AgentOutcome> outcome = ArgumentCaptor.forClass(AgentOutcome.class);
        verify(hardship).recordAgentOutcome(outcome.capture());
        assertThat(outcome.getValue().approvable()).isTrue();
        assertThat(outcome.getValue().memberMessages()).containsEntry("en", "hi").containsEntry("es", "hola");
        assertThat(outcome.getValue().citedSignals()).containsExactly("LOW_COVERAGE");
        verify(killSwitch).recordValidatorOutcome("guardian", false);
        verify(killSwitch, never()).recordGroundingFailure(any());
        verify(runs).finish(RUN, "PASSED", "APPROVABLE", null);
    }

    @Test
    void anUngroundedFigureFallsBackToTemplatesAndCountsTowardTheTrip() {
        verdict(false, false, new Check("V06_UNGROUNDED_FIGURE", false, "17500 is not in any tool result"));

        Receipt receipt = intake.submit(principal, RUN.toString(), proposal(CASE));

        assertThat(receipt.status()).isEqualTo("REJECTED");
        assertThat(receipt.templateFallback()).isTrue();
        assertThat(receipt.failed()).extracting(f -> f.code()).containsExactly("V06_UNGROUNDED_FIGURE");
        verify(killSwitch).recordValidatorOutcome("guardian", true);
        verify(killSwitch).recordGroundingFailure("guardian");
        verify(runs).finish(RUN, "FAILED_FELL_BACK", "REJECTED", null);
    }

    @Test
    void aProposalForAnotherCaseIsNeverApprovableWhateverTheValidatorSays() {
        verdict(true, false);

        Receipt receipt = intake.submit(principal, RUN.toString(), proposal(UUID.randomUUID()));

        assertThat(receipt.result()).isEqualTo("REJECTED");
        assertThat(receipt.failed()).extracting(f -> f.code()).contains("V02_TOKEN_BINDING");
        ArgumentCaptor<AgentOutcome> outcome = ArgumentCaptor.forClass(AgentOutcome.class);
        verify(hardship).recordAgentOutcome(outcome.capture());
        assertThat(outcome.getValue().caseId()).isEqualTo(CASE);
        assertThat(outcome.getValue().approvable()).isFalse();
    }

    @Test
    void shadowModeStoresTheTextButSendsTemplates() {
        verdict(true, false);
        when(killSwitch.mode("guardian")).thenReturn(KillSwitch.Mode.SHADOW);

        Receipt receipt = intake.submit(principal, RUN.toString(), proposal(CASE));

        assertThat(receipt.status()).isEqualTo("SHADOWED");
        assertThat(receipt.templateFallback()).isTrue();
        ArgumentCaptor<AgentOutcome> outcome = ArgumentCaptor.forClass(AgentOutcome.class);
        verify(hardship).recordAgentOutcome(outcome.capture());
        assertThat(outcome.getValue().shadow()).isTrue();
    }

    @Test
    void aReplayChangesNothing() {
        verdict(true, true);

        Receipt receipt = intake.submit(principal, RUN.toString(), proposal(CASE));

        assertThat(receipt.replay()).isTrue();
        verify(hardship, never()).recordAgentOutcome(any());
        verify(killSwitch, never()).recordValidatorOutcome(any(), anyBoolean());
    }

    @Test
    void anIdempotencyKeyIsRequired() {
        assertThatThrownBy(() -> intake.submit(principal, " ", proposal(CASE)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Idempotency-Key");
        verify(governance, never()).submitProposal(any());
    }

    @Test
    void aFailedRunTellsHardshipAndClosesTheTrace() {
        intake.reportFailure(CASE, RUN, "MODEL_UNAVAILABLE");

        ArgumentCaptor<AgentOutcome> outcome = ArgumentCaptor.forClass(AgentOutcome.class);
        verify(hardship).recordAgentOutcome(outcome.capture());
        assertThat(outcome.getValue().approvable()).isFalse();
        assertThat(outcome.getValue().errorCode()).isEqualTo("MODEL_UNAVAILABLE");
        verify(runs).finish(RUN, "NOT_RUN", "FAILED", "MODEL_UNAVAILABLE");
    }
}
