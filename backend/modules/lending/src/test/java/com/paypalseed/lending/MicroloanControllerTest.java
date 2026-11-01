package com.paypalseed.lending;

import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.infrastructure.persistence.InMemoryLoanApplicationRepository;
import com.paypalseed.lending.infrastructure.web.MicroloanController;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.security.ActorArgumentResolver;
import com.paypalseed.platform.security.ActorAuthentication;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MicroloanControllerTest {

    private static final UUID ANA_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB_LOW_INCOME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OFFICER_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OFFICER_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final String APPLICATION_JSON_BODY = """
        {
          "purpose": "Commercial stand mixer",
          "requestedAmountCents": 120000,
          "termMonths": 8,
          "preferredDueDay": 15,
          "payoutWallet": "VENMO",
          "fairCheckConsented": true
        }
        """;

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
                return new MemberSavingsSummary(memberId, 4, 4, 0, 7_500L, 30_000L, true);
            }
        };
        UnderwritingFacade underwritingFacade = new UnderwritingFacade() {
            @Override
            public Optional<IncomeSummary> incomeSummary(UUID memberId, LocalDate asOf) {
                return Optional.empty();
            }

            @Override
            public OptionalLong verifiedMonthlyIncomeCents(UUID memberId, LocalDate asOf) {
                return OptionalLong.of(BOB_LOW_INCOME_ID.equals(memberId) ? 40_000L : 160_000L);
            }
        };

        MicroloanApplicationService service = new MicroloanApplicationService(
            repo,
            savingsFacade,
            null,
            null,
            new FakeBusinessClock(LocalDate.of(2026, 11, 1)),
            underwritingFacade,
            null
        );

        MicroloanController controller = new MicroloanController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new ActorArgumentResolver(new ActorAuthentication("", true)))
            .setControllerAdvice(new TestErrorAdvice())
            .build();
    }

    @Test
    void getEligibility_returnsUnlockedLevel1ForAna() throws Exception {
        mockMvc.perform(asMember(ANA_ID, get("/api/v1/loans/eligibility")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberId").value(ANA_ID.toString()))
            .andExpect(jsonPath("$.eligible").value(true))
            .andExpect(jsonPath("$.tier").value("LEVEL_1"))
            .andExpect(jsonPath("$.tierLimitCents").value(150000))
            .andExpect(jsonPath("$.maxMonthlyInstalmentCents").value(15000));
    }

    @Test
    void getEligibility_ignoresIncomeAndMemberIdFromTheCaller() throws Exception {
        mockMvc.perform(asMember(BOB_LOW_INCOME_ID, get("/api/v1/loans/eligibility"))
                .param("verifiedMonthlyIncomeCents", "99999999")
                .param("memberId", ANA_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberId").value(BOB_LOW_INCOME_ID.toString()))
            .andExpect(jsonPath("$.maxMonthlyInstalmentCents").value(6000));
    }

    @Test
    void submit_ignoresIncomeAndMemberIdInTheBody() throws Exception {
        String spoofed = """
            {
              "memberId": "%s",
              "purpose": "Commercial stand mixer",
              "requestedAmountCents": 120000,
              "termMonths": 8,
              "fairCheckConsented": true,
              "verifiedMonthlyIncomeCents": 99999999
            }
            """.formatted(ANA_ID);

        mockMvc.perform(asMember(BOB_LOW_INCOME_ID, post("/api/v1/loans/applications"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(spoofed))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("sizing-rule-violation"));
    }

    @Test
    void submitApproveAndDisburse_completesTwoOfficerMakerCheckerWorkflowOverHttp() throws Exception {
        String appId = submitAsAna();

        // Officer 1 approves
        mockMvc.perform(asStaff(OFFICER_1, "OFFICER", post("/api/v1/desk/loans/applications/" + appId + "/approve")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_SECOND_APPROVAL"));

        // Officer 1 cannot countersign own approval (409 Conflict)
        mockMvc.perform(asStaff(OFFICER_1, "OFFICER", post("/api/v1/desk/loans/applications/" + appId + "/approve")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("maker-checker-same-officer"));

        // Officer 2 countersigns
        mockMvc.perform(asStaff(OFFICER_2, "CHECKER", post("/api/v1/desk/loans/applications/" + appId + "/approve")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        // A checker can't pay it out
        mockMvc.perform(asStaff(OFFICER_2, "CHECKER", post("/api/v1/desk/loans/applications/" + appId + "/disburse")))
            .andExpect(status().isForbidden());

        // Disburse
        mockMvc.perform(asStaff(OFFICER_1, "OFFICER", post("/api/v1/desk/loans/applications/" + appId + "/disburse")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISBURSED"))
            .andExpect(jsonPath("$.disbursementRef").value("loan-" + appId + "-disb"));
    }

    @Test
    void approve_cannotClaimAnotherOfficersIdInTheBody() throws Exception {
        String appId = submitAsAna();
        mockMvc.perform(asStaff(OFFICER_1, "OFFICER", post("/api/v1/desk/loans/applications/" + appId + "/approve")))
            .andExpect(status().isOk());

        mockMvc.perform(asStaff(OFFICER_1, "OFFICER", post("/api/v1/desk/loans/applications/" + appId + "/approve"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"officerId\":\"" + OFFICER_2 + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("maker-checker-same-officer"));
    }

    @Test
    void everyRouteRejectsAnonymousCallers() throws Exception {
        String appId = submitAsAna();
        List<MockHttpServletRequestBuilder> anonymous = List.of(
            get("/api/v1/loans/eligibility").param("memberId", ANA_ID.toString()),
            post("/api/v1/loans/applications").contentType(MediaType.APPLICATION_JSON).content(APPLICATION_JSON_BODY),
            get("/api/v1/loans/applications").param("memberId", ANA_ID.toString()),
            get("/api/v1/loans/applications/" + appId),
            post("/api/v1/loans/applications/" + appId + "/cooling-off-cancel").param("memberId", ANA_ID.toString()),
            get("/api/v1/desk/loans/applications"),
            post("/api/v1/desk/loans/applications/" + appId + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{\"officerId\":\"" + OFFICER_1 + "\"}"),
            post("/api/v1/desk/loans/applications/" + appId + "/reject")
                .contentType(MediaType.APPLICATION_JSON).content("{\"officerId\":\"" + OFFICER_1 + "\"}"),
            post("/api/v1/desk/loans/applications/" + appId + "/disburse")
        );

        for (MockHttpServletRequestBuilder request : anonymous) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void oldIdentityHeadersGrantNothing() throws Exception {
        mockMvc.perform(get("/api/v1/loans/eligibility").header("X-Member-Id", ANA_ID.toString()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + submitAsAna() + "/approve")
                .header("X-Staff-Id", OFFICER_1.toString()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherMembersApplicationLooksMissing() throws Exception {
        String appId = submitAsAna();

        mockMvc.perform(asMember(BOB_LOW_INCOME_ID, get("/api/v1/loans/applications/" + appId)))
            .andExpect(status().isNotFound());
        mockMvc.perform(asMember(BOB_LOW_INCOME_ID, post("/api/v1/loans/applications/" + appId + "/cooling-off-cancel")))
            .andExpect(status().isNotFound());
        mockMvc.perform(asMember(BOB_LOW_INCOME_ID, get("/api/v1/loans/applications"))
                .param("memberId", ANA_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void membersCannotUseDeskRoutes() throws Exception {
        String appId = submitAsAna();

        mockMvc.perform(asMember(ANA_ID, get("/api/v1/desk/loans/applications")))
            .andExpect(status().isForbidden());
        mockMvc.perform(asMember(ANA_ID, post("/api/v1/desk/loans/applications/" + appId + "/approve")))
            .andExpect(status().isForbidden());
        mockMvc.perform(asMember(ANA_ID, post("/api/v1/desk/loans/applications/" + appId + "/reject")))
            .andExpect(status().isForbidden());
        mockMvc.perform(asMember(ANA_ID, post("/api/v1/desk/loans/applications/" + appId + "/disburse")))
            .andExpect(status().isForbidden());
    }

    @Test
    void staffNeedTheRightRole() throws Exception {
        String appId = submitAsAna();

        mockMvc.perform(asStaff(OFFICER_1, "", get("/api/v1/desk/loans/applications")))
            .andExpect(status().isForbidden());
        mockMvc.perform(asStaff(OFFICER_1, "", get("/api/v1/loans/applications/" + appId)))
            .andExpect(status().isForbidden());
        mockMvc.perform(asStaff(OFFICER_1, "ADMIN", post("/api/v1/desk/loans/applications/" + appId + "/approve")))
            .andExpect(status().isForbidden());
        mockMvc.perform(asStaff(OFFICER_1, "CHECKER", get("/api/v1/loans/applications/" + appId)))
            .andExpect(status().isOk());
    }

    private String submitAsAna() throws Exception {
        MvcResult result = mockMvc.perform(asMember(ANA_ID, post("/api/v1/loans/applications"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(APPLICATION_JSON_BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUBMITTED"))
            .andExpect(jsonPath("$.monthlyInstalmentCents").value(15000))
            .andReturn();
        return result.getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private static MockHttpServletRequestBuilder asMember(UUID memberId, MockHttpServletRequestBuilder request) {
        return request.header("X-Seed-Member-Id", memberId.toString());
    }

    private static MockHttpServletRequestBuilder asStaff(UUID staffId, String roles, MockHttpServletRequestBuilder request) {
        return request.header("X-Seed-Staff-Id", staffId.toString()).header("X-Seed-Staff-Roles", roles);
    }
}
