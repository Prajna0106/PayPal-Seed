package com.paypalseed.lending;

import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.infrastructure.persistence.InMemoryLoanApplicationRepository;
import com.paypalseed.lending.infrastructure.web.MicroloanController;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

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

    /** The dev-header identity the running app accepts, so the controller is exercised with real Actors. */
    static class HeaderActors implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == Actor.class;
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                      WebDataBinderFactory binder) {
            String member = request.getHeader("X-Seed-Member-Id");
            if (member != null) {
                return Actor.member(UUID.fromString(member));
            }
            String staff = request.getHeader("X-Seed-Staff-Id");
            if (staff != null) {
                String roles = request.getHeader("X-Seed-Staff-Roles");
                Set<Actor.Role> granted = roles == null || roles.isBlank() ? Set.of()
                    : Arrays.stream(roles.split(",")).map(Actor.Role::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(Actor.Role.class)));
                return new Actor(Actor.Type.STAFF, UUID.fromString(staff), granted);
            }
            throw new DomainException(CommonErrors.UNAUTHENTICATED, "Sign in");
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
            .setCustomArgumentResolvers(new HeaderActors())
            .setControllerAdvice(new TestErrorAdvice())
            .build();
    }

    @Test
    void getEligibility_returnsUnlockedLevel1ForAna() throws Exception {
        mockMvc.perform(get("/api/v1/loans/eligibility")
                .header("X-Seed-Member-Id", ANA_ID.toString())
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
                .header("X-Seed-Member-Id", ANA_ID.toString())
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
                .header("X-Seed-Staff-Id", OFFICER_1.toString()).header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_SECOND_APPROVAL"));

        // Officer 1 cannot countersign own approval (409 Conflict)
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/approve")
                .header("X-Seed-Staff-Id", OFFICER_1.toString()).header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("maker-checker-same-officer"));

        // Officer 2 countersigns
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/approve")
                .header("X-Seed-Staff-Id", OFFICER_2.toString()).header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        // Disburse
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/disburse")
                .header("X-Seed-Staff-Id", OFFICER_2.toString()).header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISBURSED"))
            .andExpect(jsonPath("$.disbursementRef").value("loan-" + appId + "-disb"));
    }

    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private String submittedApplicationOfAna() throws Exception {
        String body = mockMvc.perform(post("/api/v1/loans/applications")
                .header("X-Seed-Member-Id", ANA_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"purpose":"Commercial stand mixer","requestedAmountCents":120000,"termMonths":8,"preferredDueDay":15,
                     "payoutWallet":"VENMO","fairCheckConsented":true,"verifiedMonthlyIncomeCents":160000}"""))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void aMemberIsNeverAnOfficerAndTheDeskRoutesRefuseThem() throws Exception {
        String appId = submittedApplicationOfAna();

        mockMvc.perform(get("/api/v1/desk/loans/applications").header("X-Seed-Member-Id", ANA_ID.toString()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/approve").header("X-Seed-Member-Id", ANA_ID.toString()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/reject").header("X-Seed-Member-Id", ANA_ID.toString()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/disburse").header("X-Seed-Member-Id", ANA_ID.toString()))
            .andExpect(status().isForbidden());
        // A staff member with no desk role is refused too, and an anonymous caller is not signed in.
        mockMvc.perform(get("/api/v1/desk/loans/applications").header("X-Seed-Staff-Id", OFFICER_1.toString()))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/desk/loans/applications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/desk/loans/applications").header("X-Seed-Staff-Id", OFFICER_1.toString())
                .header("X-Seed-Staff-Roles", "CHECKER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void theMemberComesFromTheActorAndAnotherMembersIdIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/loans/eligibility")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/loans/eligibility").header("X-Seed-Staff-Id", OFFICER_1.toString())
                .header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/loans/eligibility").header("X-Seed-Member-Id", ANA_ID.toString())
                .param("memberId", OTHER_ID.toString()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/loans/eligibility").header("X-Seed-Member-Id", ANA_ID.toString())
                .param("memberId", ANA_ID.toString()).param("verifiedMonthlyIncomeCents", "160000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.memberId").value(ANA_ID.toString()));
        mockMvc.perform(get("/api/v1/loans/applications").header("X-Seed-Member-Id", ANA_ID.toString())
                .param("memberId", OTHER_ID.toString()))
            .andExpect(status().isNotFound());

        // A member id in the body is the same: it has to be the caller's own.
        mockMvc.perform(post("/api/v1/loans/applications").header("X-Seed-Member-Id", ANA_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"memberId":"22222222-2222-2222-2222-222222222222","purpose":"Mixer","requestedAmountCents":120000,
                     "termMonths":8,"fairCheckConsented":true,"verifiedMonthlyIncomeCents":160000}"""))
            .andExpect(status().isNotFound());
    }

    @Test
    void anApplicationIsReadByItsOwnerOrByTheDeskAndByNoOtherMember() throws Exception {
        String appId = submittedApplicationOfAna();

        mockMvc.perform(get("/api/v1/loans/applications/" + appId).header("X-Seed-Member-Id", ANA_ID.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        mockMvc.perform(get("/api/v1/loans/applications/" + appId).header("X-Seed-Member-Id", OTHER_ID.toString()))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/loans/applications/" + appId + "/cooling-off-cancel")
                .header("X-Seed-Member-Id", OTHER_ID.toString()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/loans/applications/" + appId).header("X-Seed-Staff-Id", OFFICER_1.toString())
                .header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/loans/applications/" + appId).header("X-Seed-Staff-Id", OFFICER_1.toString()))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/loans/applications/" + appId)).andExpect(status().isUnauthorized());
    }

    @Test
    void theOfficerOnADecisionIsTheActorNotAFieldTheCallerWrote() throws Exception {
        String appId = submittedApplicationOfAna();
        String claimsToBeOfficerTwo = "{\"officerId\":\"" + OFFICER_2 + "\"}";

        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/approve")
                .header("X-Seed-Staff-Id", OFFICER_1.toString()).header("X-Seed-Staff-Roles", "OFFICER")
                .contentType(MediaType.APPLICATION_JSON).content(claimsToBeOfficerTwo))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_SECOND_APPROVAL"));
        // Officer 1 cannot become the second approver by writing officer 2's id.
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/approve")
                .header("X-Seed-Staff-Id", OFFICER_1.toString()).header("X-Seed-Staff-Roles", "OFFICER")
                .contentType(MediaType.APPLICATION_JSON).content(claimsToBeOfficerTwo))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("maker-checker-same-officer"));
        mockMvc.perform(post("/api/v1/desk/loans/applications/" + appId + "/reject")
                .header("X-Seed-Staff-Id", OFFICER_2.toString()).header("X-Seed-Staff-Roles", "CHECKER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Income not verified\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.rejectionReason").value("Income not verified"));
    }
}
