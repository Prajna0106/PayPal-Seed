package com.paypalseed.underwriting;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import com.paypalseed.underwriting.infrastructure.persistence.UnderwritingRepository;
import com.paypalseed.underwriting.infrastructure.web.UnderwritingController;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UnderwritingUnitTest {

    private static final UUID ANA_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OFFICER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

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
        UnderwritingController controller = new UnderwritingController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new TestErrorAdvice())
            .build();
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
        mockMvc.perform(post("/api/v1/underwriting/consent")
                .header("X-Member-Id", ANA_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.consented").value(true));

        // 2. Run Fair Loan Check
        String assessmentJson = """
        {
          "purpose": "Commercial stand mixer",
          "requestedAmountCents": 120000,
          "requestedTermMonths": 8,
          "monthlyIncomes": [
            {"yearMonth": "2026-05", "amountCents": 165000},
            {"yearMonth": "2026-06", "amountCents": 172000},
            {"yearMonth": "2026-07", "amountCents": 118000},
            {"yearMonth": "2026-08", "amountCents": 165000},
            {"yearMonth": "2026-09", "amountCents": 170000},
            {"yearMonth": "2026-10", "amountCents": 170000}
          ],
          "statedMonthlyIncomeCents": 160000,
          "statedMonthlyExpensesCents": 90000
        }
        """;

        MvcResult result = mockMvc.perform(post("/api/v1/underwriting/assessments")
                .header("X-Member-Id", ANA_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(assessmentJson))
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
        mockMvc.perform(post("/api/v1/underwriting/assessments/" + assessmentId + "/dispute")
                .header("X-Member-Id", ANA_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"July was a two-week vacation closure; catering invoice paid in August.\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberDisputed").value(true))
            .andExpect(jsonPath("$.memberDisputeNote").value("July was a two-week vacation closure; catering invoice paid in August."));

        // 4. Loan officer reviews and records decision
        mockMvc.perform(post("/api/v1/desk/underwriting/assessments/" + assessmentId + "/decision")
                .header("X-Staff-Id", OFFICER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\",\"reason\":\"4/4 months on-time saving and steady bakery sales support $150/month.\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.officerDecision").value("APPROVED"))
            .andExpect(jsonPath("$.decidedLoanCents").value(120000))
            .andExpect(jsonPath("$.officerReason").value("4/4 months on-time saving and steady bakery sales support $150/month."));

        // 5. Revoke consent
        mockMvc.perform(delete("/api/v1/underwriting/consent")
                .header("X-Member-Id", ANA_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.consented").value(false));
    }
}
