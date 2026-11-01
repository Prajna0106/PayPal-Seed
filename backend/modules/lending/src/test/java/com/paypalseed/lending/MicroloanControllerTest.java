package com.paypalseed.lending;

import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.infrastructure.persistence.InMemoryLoanApplicationRepository;
import com.paypalseed.lending.infrastructure.web.MicroloanController;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import java.time.LocalDate;
import java.util.HashMap;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MicroloanControllerTest {

    private static final UUID ANA_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OFFICER_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OFFICER_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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
            body.put("details", ex.details());
            return ResponseEntity.status(status).body(body);
        }
    }

    @BeforeEach
    void setUp() {
        InMemoryLoanApplicationRepository repo = new InMemoryLoanApplicationRepository();
        SavingsFacade savingsFacade = new SavingsFacade() {
            @Override
            public void recordDepositBySubscription(String subscriptionId, Money amount, String payPalCaptureId) {}

            @Override
            public void pauseGoalBySubscription(String subscriptionId) {}

            @Override
            public MemberSavingsSummary getMemberSavingsSummary(String memberId) {
                if (ANA_ID.toString().equals(memberId)) {
                    return new MemberSavingsSummary(memberId, 4, 4, 0, 7_500L, 30_000L, true);
                }
                return new MemberSavingsSummary(memberId, 1, 1, 0, 5_000L, 5_000L, false);
            }
        };

        MicroloanApplicationService service = new MicroloanApplicationService(
            repo,
            savingsFacade,
            null,
            null,
            new FakeBusinessClock(LocalDate.of(2026, 11, 1))
        );

        MicroloanController controller = new MicroloanController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new TestErrorAdvice())
            .build();
    }

    @Test
    void getEligibility_returnsUnlockedLevel1ForAna() throws Exception {
        mockMvc.perform(get("/api/v1/loans/eligibility")
                .header("X-Member-Id", ANA_ID.toString())
                .param("verifiedMonthlyIncomeCents", "160000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eligible").value(true))
            .andExpect(jsonPath("$.tier").value("LEVEL_1"))
            .andExpect(jsonPath("$.tierLimitCents").value(150000))
            .andExpect(jsonPath("$.maxMonthlyInstalmentCents").value(15000));
    }

    @Test
    void submitApproveAndDisburse_completesTwoOfficerMakerCheckerWorkflowOverHttp() throws Exception {
        String requestJson = """
        {
          "purpose": "Commercial stand mixer",
          "requestedAmountCents": 120000,
          "termMonths": 8,
          "preferredDueDay": 15,
          "payoutWallet": "VENMO",
          "fairCheckConsented": true,
          "verifiedMonthlyIncomeCents": 160000
        }
        """;

        MvcResult result = mockMvc.perform(post("/api/v1/loans/applications")
                .header("X-Member-Id", ANA_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUBMITTED"))
            .andExpect(jsonPath("$.monthlyInstalmentCents").value(15000))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        String appId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // Officer 1 approves
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/approve")
                .header("X-Staff-Id", OFFICER_1.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_SECOND_APPROVAL"));

        // Officer 1 cannot countersign own approval (409 Conflict)
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/approve")
                .header("X-Staff-Id", OFFICER_1.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("maker-checker-same-officer"));

        // Officer 2 countersigns
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/approve")
                .header("X-Staff-Id", OFFICER_2.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        // Disburse
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/disburse"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISBURSED"))
            .andExpect(jsonPath("$.disbursementRef").value("loan-" + appId + "-disb"));
    }
}
