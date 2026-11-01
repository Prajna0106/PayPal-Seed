package com.paypalseed.underwriting;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.security.ActorArgumentResolver;
import com.paypalseed.platform.security.ActorAuthentication;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import com.paypalseed.underwriting.infrastructure.persistence.UnderwritingRepository;
import com.paypalseed.underwriting.infrastructure.web.UnderwritingController;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UnderwritingUnitTest {

    private static final UUID ANA_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OFFICER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final List<IncomeMonthDto> RECORDED_INCOME = List.of(
        new IncomeMonthDto("2026-05", 165_000L),
        new IncomeMonthDto("2026-06", 172_000L),
        new IncomeMonthDto("2026-07", 118_000L),
        new IncomeMonthDto("2026-08", 165_000L),
        new IncomeMonthDto("2026-09", 170_000L),
        new IncomeMonthDto("2026-10", 170_000L)
    );
    private static final String ASSESSMENT_JSON = """
        {
          "purpose": "Commercial stand mixer",
          "requestedAmountCents": 120000,
          "requestedTermMonths": 8,
          "statedMonthlyIncomeCents": 160000,
          "statedMonthlyExpensesCents": 90000
        }
        """;

    private UnderwritingRepository repository;
    private FairLoanCheckService service;
    private MockMvc mockMvc;

    @ControllerAdvice
    static class TestErrorAdvice {
        @ExceptionHandler(DomainException.class)
        public ResponseEntity<Map<String, Object>> handleDomain(DomainException ex) {
            ErrorCode code = ex.errorCode();
            HttpStatus status = switch (code.kind()) {
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case CONFLICT -> HttpStatus.CONFLICT;
                case FORBIDDEN -> HttpStatus.FORBIDDEN;
                case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
                case VALIDATION -> HttpStatus.UNPROCESSABLE_ENTITY;
                case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.BAD_REQUEST;
            };
            Map<String, Object> body = new HashMap<>();
            body.put("code", code.code());
            body.put("detail", ex.getMessage());
            return ResponseEntity.status(status).body(body);
        }
    }

    @BeforeEach
    void setUp() {
        repository = new UnderwritingRepository();
        SavingsFacade savingsFacade = new SavingsFacade() {
            @Override
            public void recordDepositBySubscription(String subscriptionId, Money amount, String payPalCaptureId) {}

            @Override
            public void pauseGoalBySubscription(String subscriptionId) {}

            @Override
            public MemberSavingsSummary getMemberSavingsSummary(String memberId) {
                return new MemberSavingsSummary(memberId, 4, 4, 0, 7_500L, 30_000L, true);
            }
        };

        service = new FairLoanCheckService(repository, savingsFacade);
        UnderwritingFacade underwriting = new UnderwritingFacade() {
            @Override
            public Optional<IncomeSummary> incomeSummary(UUID memberId, LocalDate asOf) {
                return Optional.empty();
            }

            @Override
            public List<IncomeMonthDto> consentedMonthlyIncomes(UUID memberId) {
                return RECORDED_INCOME;
            }
        };
        UnderwritingController controller = new UnderwritingController(service, underwriting);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new ActorArgumentResolver(new ActorAuthentication("", true)))
            .setControllerAdvice(new TestErrorAdvice())
            .build();
    }

    private static MockHttpServletRequestBuilder asMember(UUID memberId, MockHttpServletRequestBuilder request) {
        return request.header("X-Seed-Member-Id", memberId.toString());
    }

    private static MockHttpServletRequestBuilder asStaff(String roles, MockHttpServletRequestBuilder request) {
        return request.header("X-Seed-Staff-Id", OFFICER_ID.toString()).header("X-Seed-Staff-Roles", roles);
    }

    private String runAssessmentAs(UUID memberId) throws Exception {
        mockMvc.perform(asMember(memberId, post("/api/v1/underwriting/consent"))).andExpect(status().isOk());
        MvcResult result = mockMvc.perform(asMember(memberId, post("/api/v1/underwriting/assessments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ASSESSMENT_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"assessmentId\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void assessmentUsesRecordedIncome_notIncomeOrIdsFromTheRequest() throws Exception {
        mockMvc.perform(asMember(ANA_ID, post("/api/v1/underwriting/consent"))).andExpect(status().isOk());

        mockMvc.perform(asMember(ANA_ID, post("/api/v1/underwriting/assessments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "memberId": "%s",
                      "purpose": "Fake riches",
                      "requestedAmountCents": 120000,
                      "requestedTermMonths": 8,
                      "monthlyIncomes": [{"yearMonth": "2026-10", "amountCents": 99999999}],
                      "statedMonthlyIncomeCents": 99999999,
                      "statedMonthlyExpensesCents": 0,
                      "completedLoansOnTime": 50
                    }
                    """.formatted(BOB_ID)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.memberId").value(ANA_ID.toString()))
            .andExpect(jsonPath("$.averageMonthlyIncomeCents").value(160000))
            .andExpect(jsonPath("$.usedFallbackStatedIncome").value(false));
    }

    @Test
    void everyRouteRejectsAnonymousCallers() throws Exception {
        String id = runAssessmentAs(ANA_ID);
        List<MockHttpServletRequestBuilder> anonymous = List.of(
            get("/api/v1/underwriting/consent").param("memberId", ANA_ID.toString()),
            post("/api/v1/underwriting/consent").param("memberId", ANA_ID.toString()),
            delete("/api/v1/underwriting/consent").param("memberId", ANA_ID.toString()),
            post("/api/v1/underwriting/assessments").contentType(MediaType.APPLICATION_JSON).content(ASSESSMENT_JSON),
            get("/api/v1/underwriting/assessments").param("memberId", ANA_ID.toString()),
            get("/api/v1/underwriting/assessments/" + id),
            post("/api/v1/underwriting/assessments/" + id + "/dispute").param("memberId", ANA_ID.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"),
            get("/api/v1/desk/underwriting/assessments"),
            post("/api/v1/desk/underwriting/assessments/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"officerId\":\"" + OFFICER_ID + "\",\"decision\":\"APPROVED\",\"reason\":\"x\"}")
        );

        for (MockHttpServletRequestBuilder request : anonymous) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void oldIdentityHeadersGrantNothing() throws Exception {
        mockMvc.perform(post("/api/v1/underwriting/consent").header("X-Member-Id", ANA_ID.toString()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/desk/underwriting/assessments").header("X-Staff-Id", OFFICER_ID.toString()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherMembersAssessmentAndConsentAreOutOfReach() throws Exception {
        String id = runAssessmentAs(ANA_ID);

        mockMvc.perform(asMember(BOB_ID, get("/api/v1/underwriting/assessments/" + id)))
            .andExpect(status().isNotFound());
        mockMvc.perform(asMember(BOB_ID, post("/api/v1/underwriting/assessments/" + id + "/dispute"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(asMember(BOB_ID, get("/api/v1/underwriting/assessments")).param("memberId", ANA_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(asMember(BOB_ID, delete("/api/v1/underwriting/consent")).param("memberId", ANA_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberId").value(BOB_ID.toString()));
        mockMvc.perform(asMember(ANA_ID, get("/api/v1/underwriting/consent")))
            .andExpect(jsonPath("$.consented").value(true));
    }

    @Test
    void deskRoutesNeedTheRightRole() throws Exception {
        String id = runAssessmentAs(ANA_ID);
        String decision = "{\"decision\":\"APPROVED\",\"reason\":\"x\"}";

        mockMvc.perform(asMember(ANA_ID, get("/api/v1/desk/underwriting/assessments")))
            .andExpect(status().isForbidden());
        mockMvc.perform(asMember(ANA_ID, post("/api/v1/desk/underwriting/assessments/" + id + "/decision"))
                .contentType(MediaType.APPLICATION_JSON).content(decision))
            .andExpect(status().isForbidden());
        mockMvc.perform(asStaff("", get("/api/v1/underwriting/assessments/" + id)))
            .andExpect(status().isForbidden());
        mockMvc.perform(asStaff("ADMIN", post("/api/v1/desk/underwriting/assessments/" + id + "/decision"))
                .contentType(MediaType.APPLICATION_JSON).content(decision))
            .andExpect(status().isForbidden());
        mockMvc.perform(asStaff("CHECKER", get("/api/v1/underwriting/assessments/" + id)))
            .andExpect(status().isOk());
    }

    @Test
    void officerDecisionRecordsTheSignedInOfficer_notOneFromTheBody() throws Exception {
        String id = runAssessmentAs(ANA_ID);

        mockMvc.perform(asStaff("OFFICER", post("/api/v1/desk/underwriting/assessments/" + id + "/decision"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"officerId\":\"" + BOB_ID + "\",\"decision\":\"APPROVED\",\"reason\":\"steady\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.officerId").value(OFFICER_ID.toString()));
    }

    @Test
    void requiresExplicitConsentBeforeRunningFairCheck_andStopsWhenRevoked() {
        RunFairCheckCommand cmd = new RunFairCheckCommand(
            UUID.randomUUID(),
            "Commercial stand mixer",
            120_000L,
            8,
            List.of(new IncomeMonthDto("2026-10", 160_000L)),
            160_000L,
            80_000L,
            0,
            false
        );

        assertThatThrownBy(() -> service.runFairLoanCheck(ANA_ID, cmd))
            .isInstanceOf(DomainException.class)
            .satisfies(ex -> assertThat(((DomainException) ex).errorCode()).isEqualTo(UnderwritingErrors.CONSENT_REQUIRED));

        service.grantConsent(ANA_ID);
        FairCheckCardView card = service.runFairLoanCheck(ANA_ID, cmd);
        assertThat(card.recommended()).isTrue();
        assertThat(card.suggestedLoanCents()).isEqualTo(120_000L);

        // Revoke consent -> subsequent check is blocked
        service.revokeConsent(ANA_ID);
        assertThatThrownBy(() -> service.runFairLoanCheck(ANA_ID, cmd))
            .isInstanceOf(DomainException.class)
            .satisfies(ex -> assertThat(((DomainException) ex).errorCode()).isEqualTo(UnderwritingErrors.CONSENT_REQUIRED));
    }

    @Test
    void memberDisputeAndOfficerDecision_areRecordedWithAuditReasonsOverHttp() throws Exception {
        // 1. Grant consent
        mockMvc.perform(asMember(ANA_ID, post("/api/v1/underwriting/consent")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.consented").value(true));

        // 2. Run Fair Loan Check
        MvcResult result = mockMvc.perform(asMember(ANA_ID, post("/api/v1/underwriting/assessments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ASSESSMENT_JSON))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.recommended").value(true))
            .andExpect(jsonPath("$.averageMonthlyIncomeCents").value(160000))
            .andExpect(jsonPath("$.lowestMonthlyIncomeCents").value(118000))
            .andExpect(jsonPath("$.instalmentCapCents").value(15000))
            .andExpect(jsonPath("$.suggestedLoanCents").value(120000))
            .andExpect(jsonPath("$.suggestedTermMonths").value(8))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        String assessmentId = body.replaceAll(".*\"assessmentId\":\"([^\"]+)\".*", "$1");

        // 3. Member flags a number dispute note
        mockMvc.perform(asMember(ANA_ID, post("/api/v1/underwriting/assessments/" + assessmentId + "/dispute"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"July was a two-week vacation closure; catering invoice paid in August.\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberDisputed").value(true))
            .andExpect(jsonPath("$.memberDisputeNote").value("July was a two-week vacation closure; catering invoice paid in August."));

        // 4. Loan officer reviews and records decision
        mockMvc.perform(asStaff("OFFICER", post("/api/v1/desk/underwriting/assessments/" + assessmentId + "/decision"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\",\"reason\":\"4/4 months on-time saving and steady bakery sales support $150/month.\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.officerDecision").value("APPROVED"))
            .andExpect(jsonPath("$.decidedLoanCents").value(120000))
            .andExpect(jsonPath("$.officerReason").value("4/4 months on-time saving and steady bakery sales support $150/month."));

        // 5. Revoke consent
        mockMvc.perform(asMember(ANA_ID, delete("/api/v1/underwriting/consent")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.consented").value(false));
    }
}
