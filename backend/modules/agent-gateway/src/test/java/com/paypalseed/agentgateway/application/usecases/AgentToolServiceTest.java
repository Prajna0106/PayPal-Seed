package com.paypalseed.agentgateway.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.api.AgentTools;
import com.paypalseed.agentgateway.api.AgentTools.ToolResult;
import com.paypalseed.agentgateway.domain.model.AgentErrors;
import com.paypalseed.hardship.api.HardshipFacade;
import com.paypalseed.hardship.api.dto.HardshipDtos.OptionView;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentToolServiceTest {

    private static final UUID RUN = UUID.fromString("0197f3a9-0000-7000-8000-00000000a001");
    private static final UUID LOAN = UUID.fromString("0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e01");
    private static final UUID OTHER_LOAN = UUID.fromString("0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e99");
    private static final UUID CASE = UUID.fromString("0197f3a8-1111-7000-8000-00000000c001");
    private static final UUID MEMBER = UUID.fromString("0197f3a0-aaaa-7000-8000-000000000001");
    private static final LocalDate TODAY = LocalDate.parse("2027-03-10");

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private AgentTokens tokens;
    private AgentRuns runs;
    private AgentGuard guard;
    private LendingFacade lending;
    private HardshipFacade hardship;
    private UnderwritingFacade underwriting;
    private AgentToolService service;
    private AgentPrincipal principal;

    @BeforeEach
    void setUp() {
        tokens = mock(AgentTokens.class);
        runs = mock(AgentRuns.class);
        guard = mock(AgentGuard.class);
        lending = mock(LendingFacade.class);
        hardship = mock(HardshipFacade.class);
        underwriting = mock(UnderwritingFacade.class);
        BusinessClock clock = mock(BusinessClock.class);
        when(clock.today()).thenReturn(TODAY);
        service = new AgentToolService(tokens, runs, guard, lending, hardship, underwriting, clock, json);
        principal = new AgentPrincipal(RUN, LOAN, CASE, AgentTokens.ALL_SCOPES);
        when(tokens.verify("good")).thenReturn(Optional.of(principal));
        when(tokens.verify("bad")).thenReturn(Optional.empty());
        when(guard.requireAgentsOn(any())).thenAnswer(i -> i.getArgument(0));
        when(lending.loan(LOAN)).thenReturn(Optional.of(new LoanSummary(LOAN, MEMBER, 120000, 90000, "ACTIVE", "AUTOPAY",
                "CURRENT", 0, 1)));
    }

    private static String args(UUID loanId) {
        return "{\"loanId\":\"" + loanId + "\"}";
    }

    @Test
    void anotherLoanAnswersNotFoundLikeAMissingOne() {
        ToolResult result = service.call("good", AgentTools.GET_LOAN_SUMMARY, args(OTHER_LOAN));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("not-found");
        verify(lending, never()).loan(any());
    }

    @Test
    void anUnknownOrRevokedTokenIsRefused() {
        ToolResult result = service.call("bad", AgentTools.GET_LOAN_SUMMARY, args(LOAN));

        assertThat(result.errorCode()).isEqualTo("unauthenticated");
        verifyNoInteractions(lending, hardship, underwriting);
    }

    @Test
    void aMissingScopeIsRefused() {
        when(tokens.verify("narrow")).thenReturn(Optional.of(new AgentPrincipal(RUN, LOAN, CASE, Set.of(AgentTokens.LOAN_READ))));

        assertThat(service.call("narrow", AgentTools.GET_INCOME_SUMMARY, args(LOAN)).errorCode()).isEqualTo("insufficient-scope");
        verifyNoInteractions(underwriting);
    }

    @Test
    void theSeventhCallOfARunIsRefusedBeforeAnythingIsRead() {
        when(runs.toolCallCount(RUN)).thenReturn(AgentTools.MAX_CALLS_PER_RUN);

        ToolResult result = service.call("good", AgentTools.GET_LOAN_SUMMARY, args(LOAN));

        assertThat(result.errorCode()).isEqualTo(AgentErrors.TOOL_CALL_LIMIT.code());
        verify(lending, never()).loan(any());
        verify(runs, never()).recordToolCall(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void theKillSwitchStopsToolCalls() {
        when(guard.requireAgentsOn(any())).thenThrow(new DomainException(AgentErrors.AGENT_DISABLED, "off"));

        assertThat(service.call("good", AgentTools.GET_LOAN_SUMMARY, args(LOAN)).errorCode()).isEqualTo("agent-disabled");
    }

    @Test
    void malformedArgumentsAreRefusedWithoutAReadingAnything() {
        assertThat(service.call("good", AgentTools.GET_LOAN_SUMMARY, "[1,2]").errorCode()).isEqualTo("invalid-arguments");
        assertThat(service.call("good", AgentTools.GET_LOAN_SUMMARY, "{oops").errorCode()).isEqualTo("invalid-arguments");
        verifyNoInteractions(lending);
    }

    @Test
    void incomeIsSummarisedInWholeDollarsAndNeedsConsent() throws Exception {
        List<Long> weeks = List.of(20050L, 20000L, 19999L, 21000L, 20000L, 20500L, 19800L, 20100L, 20000L, 9650L, 9600L,
                9700L, 9599L);
        when(underwriting.incomeSummary(MEMBER, TODAY)).thenReturn(Optional.of(new IncomeSummary(weeks, 5, 13)));

        ToolResult result = service.call("good", AgentTools.GET_INCOME_SUMMARY, args(LOAN));

        JsonNode body = json.readTree(result.json());
        assertThat(body.path("weeklyInflowCents")).allSatisfy(w -> assertThat(w.asLong() % 100).isZero());
        assertThat(body.path("medianWeeklyInflowCents").asLong()).isEqualTo(20000);
        assertThat(body.path("usualPaydayWeekday").asText()).isEqualTo("FRIDAY");
        verify(runs).recordToolCall(eq(RUN), eq(AgentTools.GET_INCOME_SUMMARY), anyLong(), anyString());

        when(underwriting.incomeSummary(MEMBER, TODAY)).thenReturn(Optional.empty());
        assertThat(service.call("good", AgentTools.GET_INCOME_SUMMARY, args(LOAN)).errorCode()).isEqualTo("no-consent");
    }

    @Test
    void offeredOptionsUseContractNamesAndIsoDates() throws Exception {
        when(hardship.options(CASE)).thenReturn(List.of(new OptionView("C", "REAMORTIZE", Map.of("extraMonths", 2), "ab12",
                "MAKER_CHECKER", true, true, List.of(), Map.of("lastDueDate", LocalDate.parse("2027-10-20").toEpochDay(),
                "maxInstalmentCents", 11300L))));

        JsonNode option = json.readTree(service.call("good", AgentTools.LIST_OFFERED_OPTIONS, args(LOAN)).json())
                .path("options").get(0);

        assertThat(option.path("option").asText()).isEqualTo("EXTEND_PLAN");
        assertThat(option.path("approvalTier").asText()).isEqualTo("OFFICER_AND_CHECKER");
        assertThat(option.path("previewHash").asText()).isEqualTo("sha256:ab12");
        assertThat(option.path("summary").path("lastDueDate").asText()).isEqualTo("2027-10-20");
        assertThat(option.path("summary").path("maxInstalmentCents").asLong()).isEqualTo(11300);
        assertThat(option.path("recommended").asBoolean()).isTrue();
    }

    @Test
    void previewingAnOptionThatIsNotOfferedIsNotFound() {
        when(hardship.options(CASE)).thenReturn(List.of());

        ToolResult result = service.call("good", AgentTools.PREVIEW_OPTION,
                "{\"loanId\":\"" + LOAN + "\",\"option\":\"PAUSE_ONE_MONTH\"}");

        assertThat(result.errorCode()).isEqualTo("option-not-offered");
    }

    @Test
    void theMemberIsAPseudonymNeverTheId() throws Exception {
        when(lending.currentSchedule(LOAN)).thenReturn(new com.paypalseed.lending.api.dto.LendingDtos.ScheduleView(LOAN, 1,
                "ORIGINAL", TODAY, 120000, 90000, 0, "ACTIVE", "AUTOPAY", 0, "CURRENT", List.of()));

        String body = service.call("good", AgentTools.GET_LOAN_SUMMARY, args(LOAN)).json();

        assertThat(body).doesNotContain(MEMBER.toString());
        assertThat(json.readTree(body).path("memberRef").asText()).matches("m_[A-Za-z0-9_-]{10}")
                .isEqualTo(AgentToolService.memberRef(MEMBER));
    }
}
