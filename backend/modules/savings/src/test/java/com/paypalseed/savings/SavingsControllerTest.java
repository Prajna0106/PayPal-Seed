package com.paypalseed.savings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.platform.security.ActorArgumentResolver;
import com.paypalseed.platform.security.ActorAuthentication;
import com.paypalseed.savings.api.dto.CreateGoalRequest;
import com.paypalseed.savings.api.dto.WithdrawRequest;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.ProcessWithdrawalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.infrastructure.persistence.SavingsRepository;
import com.paypalseed.savings.infrastructure.web.SavingsController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SavingsControllerTest {

    private static final String ANA = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String OFFICER = "33333333-3333-3333-3333-333333333333";
    private static final String INTERNAL_TOKEN = "test-internal-token-0123456789";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private SavingsRepository repository;
    private DummyEventOutbox outbox;

    @BeforeEach
    void setUp() {
        repository = new SavingsRepository();
        outbox = new DummyEventOutbox();

        CreateGoalUseCase createGoalUseCase = new CreateGoalUseCase(repository, outbox);
        RecordDepositUseCase recordDepositUseCase = new RecordDepositUseCase(repository, outbox);
        ProcessWithdrawalUseCase processWithdrawalUseCase = new ProcessWithdrawalUseCase(repository, outbox);
        PauseResumeGoalUseCase pauseResumeGoalUseCase = new PauseResumeGoalUseCase(repository);

        SavingsController controller = new SavingsController(
            createGoalUseCase,
            recordDepositUseCase,
            processWithdrawalUseCase,
            pauseResumeGoalUseCase,
            repository
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new ActorArgumentResolver(new ActorAuthentication(INTERNAL_TOKEN, true)))
            .setControllerAdvice(new DomainErrors())
            .build();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void createsSavingsGoalForTheSignedInMember() throws Exception {
        CreateGoalRequest request = new CreateGoalRequest(
            "Baking Mixer",
            BigDecimal.valueOf(1200.00),
            BigDecimal.valueOf(75.00),
            "USD",
            LocalDate.now().plusMonths(8),
            "sub-12345"
        );

        mockMvc.perform(asMember(ANA, post("/api/v1/savings/goals"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.memberId").value(ANA))
            .andExpect(jsonPath("$.name").value("Baking Mixer"))
            .andExpect(jsonPath("$.targetAmount").value(1200.00))
            .andExpect(jsonPath("$.monthlyAmount").value(75.00))
            .andExpect(jsonPath("$.currentBalance").value(0.00))
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        org.junit.jupiter.api.Assertions.assertEquals(1, outbox.events.size());
    }

    @Test
    void ignoresMemberIdSentInTheBody() throws Exception {
        Map<String, Object> body = Map.of(
            "memberId", BOB,
            "name", "Spoofed",
            "targetAmount", 100,
            "monthlyAmount", 10
        );

        mockMvc.perform(asMember(ANA, post("/api/v1/savings/goals"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.memberId").value(ANA));
    }

    @Test
    void rejectsCreationWhenMonthlyDepositBelowFiveDollars() throws Exception {
        CreateGoalRequest request = new CreateGoalRequest(
            "Coffee Fund",
            BigDecimal.valueOf(50.00),
            BigDecimal.valueOf(3.50), // below $5
            "USD",
            null,
            null
        );

        mockMvc.perform(asMember(ANA, post("/api/v1/savings/goals"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("Minimum monthly savings amount is $5.00")));
    }

    @Test
    void performsDepositTopUpAndWithdrawalLifecycle() throws Exception {
        String goalId = createGoal(ANA, "Oven Repair");

        // 1. Top-up deposit $100, by a service
        topUp(asService(post("/api/v1/savings/goals/" + goalId + "/topup")), 100.00)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentBalance").value(100.00));

        // 2. Withdraw $30
        WithdrawRequest withdrawReq = new WithdrawRequest(BigDecimal.valueOf(30.00), "USD", "payout-30");
        mockMvc.perform(asMember(ANA, post("/api/v1/savings/goals/" + goalId + "/withdraw"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(withdrawReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentBalance").value(70.00));

        // 3. Pause goal
        mockMvc.perform(asMember(ANA, post("/api/v1/savings/goals/" + goalId + "/pause")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"));

        // 4. Resume goal
        mockMvc.perform(asMember(ANA, post("/api/v1/savings/goals/" + goalId + "/resume")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void listsGoalsByMember() throws Exception {
        createGoal(ANA, "Goal A");
        createGoal(ANA, "Goal B");

        mockMvc.perform(asMember(ANA, get("/api/v1/savings/members/" + ANA + "/goals")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].name").value("Goal A"))
            .andExpect(jsonPath("$[1].name").value("Goal B"));
    }

    @Test
    void everyRouteRejectsAnonymousCallers() throws Exception {
        String goalId = createGoal(ANA, "Private");
        List<MockHttpServletRequestBuilder> anonymous = List.of(
            post("/api/v1/savings/goals").contentType(MediaType.APPLICATION_JSON).content(
                objectMapper.writeValueAsString(new CreateGoalRequest(
                    "G", BigDecimal.valueOf(100), BigDecimal.valueOf(10), "USD", null, null))),
            get("/api/v1/savings/goals/" + goalId),
            get("/api/v1/savings/members/" + ANA + "/goals"),
            post("/api/v1/savings/goals/" + goalId + "/pause"),
            post("/api/v1/savings/goals/" + goalId + "/resume"),
            post("/api/v1/savings/goals/" + goalId + "/withdraw").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new WithdrawRequest(BigDecimal.TEN, "USD", null))),
            post("/api/v1/savings/goals/" + goalId + "/topup").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("amount", 10)))
        );

        for (MockHttpServletRequestBuilder request : anonymous) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anotherMembersGoalLooksMissing() throws Exception {
        String goalId = createGoal(ANA, "Private");
        topUp(asService(post("/api/v1/savings/goals/" + goalId + "/topup")), 100.00).andExpect(status().isOk());

        mockMvc.perform(asMember(BOB, get("/api/v1/savings/goals/" + goalId)))
            .andExpect(status().isNotFound());
        mockMvc.perform(asMember(BOB, get("/api/v1/savings/members/" + ANA + "/goals")))
            .andExpect(status().isNotFound());
        mockMvc.perform(asMember(BOB, post("/api/v1/savings/goals/" + goalId + "/pause")))
            .andExpect(status().isNotFound());
        mockMvc.perform(asMember(BOB, post("/api/v1/savings/goals/" + goalId + "/resume")))
            .andExpect(status().isNotFound());
        mockMvc.perform(asMember(BOB, post("/api/v1/savings/goals/" + goalId + "/withdraw"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new WithdrawRequest(BigDecimal.TEN, "USD", null))))
            .andExpect(status().isNotFound());

        mockMvc.perform(asMember(ANA, get("/api/v1/savings/goals/" + goalId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentBalance").value(100.00))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void membersCannotTopUpEvenTheirOwnGoal() throws Exception {
        String goalId = createGoal(ANA, "No free money");

        topUp(asMember(ANA, post("/api/v1/savings/goals/" + goalId + "/topup")), 1000.00)
            .andExpect(status().isForbidden());

        mockMvc.perform(asMember(ANA, get("/api/v1/savings/goals/" + goalId)))
            .andExpect(jsonPath("$.currentBalance").value(0.00));
    }

    @Test
    void staffCanReadGoalsButNotMoveMoney() throws Exception {
        String goalId = createGoal(ANA, "Reviewed");

        mockMvc.perform(asStaff("OFFICER", get("/api/v1/savings/goals/" + goalId)))
            .andExpect(status().isOk());
        mockMvc.perform(asStaff("OFFICER", get("/api/v1/savings/members/" + ANA + "/goals")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(asStaff("OFFICER", post("/api/v1/savings/goals/" + goalId + "/withdraw"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new WithdrawRequest(BigDecimal.TEN, "USD", null))))
            .andExpect(status().isForbidden());
        mockMvc.perform(asStaff("", get("/api/v1/savings/goals/" + goalId)))
            .andExpect(status().isForbidden());
    }

    private String createGoal(String memberId, String name) throws Exception {
        CreateGoalRequest request = new CreateGoalRequest(
            name, BigDecimal.valueOf(500.00), BigDecimal.valueOf(50.00), "USD", null, null);
        String response = mockMvc.perform(asMember(memberId, post("/api/v1/savings/goals"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private ResultActions topUp(MockHttpServletRequestBuilder request, double amount) throws Exception {
        return mockMvc.perform(request
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("amount", amount, "payPalCaptureId", "cap-" + amount))));
    }

    private static MockHttpServletRequestBuilder asMember(String memberId, MockHttpServletRequestBuilder request) {
        return request.header("X-Seed-Member-Id", memberId);
    }

    private static MockHttpServletRequestBuilder asStaff(String roles, MockHttpServletRequestBuilder request) {
        return request.header("X-Seed-Staff-Id", OFFICER).header("X-Seed-Staff-Roles", roles);
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + INTERNAL_TOKEN);
    }

    /** Stands in for the app's error handler, which isn't on this module's classpath. */
    @RestControllerAdvice
    static class DomainErrors {
        @ExceptionHandler(DomainException.class)
        ResponseEntity<Void> handle(DomainException e) {
            HttpStatus status = switch (e.errorCode().code()) {
                case "unauthenticated" -> HttpStatus.UNAUTHORIZED;
                case "forbidden" -> HttpStatus.FORBIDDEN;
                case "not-found" -> HttpStatus.NOT_FOUND;
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
            return ResponseEntity.status(status).build();
        }
    }

    static class DummyEventOutbox implements EventOutbox {
        final List<Object> events = new ArrayList<>();
        @Override
        public void publish(Object event) {
            events.add(event);
        }
    }
}
