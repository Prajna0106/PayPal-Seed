package com.paypalseed.savings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paypalseed.platform.messaging.EventOutbox;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SavingsControllerTest {

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

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void createsSavingsGoalSuccessfully() throws Exception {
        CreateGoalRequest request = new CreateGoalRequest(
            "ana-baker",
            "Baking Mixer",
            BigDecimal.valueOf(1200.00),
            BigDecimal.valueOf(75.00),
            "USD",
            LocalDate.now().plusMonths(8),
            "sub-12345"
        );

        mockMvc.perform(post("/api/v1/savings/goals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.memberId").value("ana-baker"))
            .andExpect(jsonPath("$.name").value("Baking Mixer"))
            .andExpect(jsonPath("$.targetAmount").value(1200.00))
            .andExpect(jsonPath("$.monthlyAmount").value(75.00))
            .andExpect(jsonPath("$.currentBalance").value(0.00))
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        org.junit.jupiter.api.Assertions.assertEquals(1, outbox.events.size());
    }

    @Test
    void rejectsCreationWhenMonthlyDepositBelowFiveDollars() throws Exception {
        CreateGoalRequest request = new CreateGoalRequest(
            "ana-baker",
            "Coffee Fund",
            BigDecimal.valueOf(50.00),
            BigDecimal.valueOf(3.50), // below $5
            "USD",
            null,
            null
        );

        mockMvc.perform(post("/api/v1/savings/goals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("Minimum monthly savings amount is $5.00")));
    }

    @Test
    void performsDepositTopUpAndWithdrawalLifecycle() throws Exception {
        // 1. Create Goal
        CreateGoalRequest createReq = new CreateGoalRequest(
            "ana-baker",
            "Oven Repair",
            BigDecimal.valueOf(500.00),
            BigDecimal.valueOf(50.00),
            "USD",
            null,
            "sub-oven"
        );

        String createResp = mockMvc.perform(post("/api/v1/savings/goals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String goalId = objectMapper.readTree(createResp).get("id").asText();

        // 2. Top-up deposit $100
        mockMvc.perform(post("/api/v1/savings/goals/" + goalId + "/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("amount", 100.00, "payPalCaptureId", "cap-100"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentBalance").value(100.00));

        // 3. Withdraw $30
        WithdrawRequest withdrawReq = new WithdrawRequest(BigDecimal.valueOf(30.00), "USD", "payout-30");
        mockMvc.perform(post("/api/v1/savings/goals/" + goalId + "/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(withdrawReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentBalance").value(70.00));

        // 4. Pause goal
        mockMvc.perform(post("/api/v1/savings/goals/" + goalId + "/pause"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"));

        // 5. Resume goal
        mockMvc.perform(post("/api/v1/savings/goals/" + goalId + "/resume"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void listsGoalsByMember() throws Exception {
        mockMvc.perform(post("/api/v1/savings/goals")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateGoalRequest(
                "member-xyz", "Goal A", BigDecimal.valueOf(100), BigDecimal.valueOf(10), "USD", null, "s1"
            ))));

        mockMvc.perform(post("/api/v1/savings/goals")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateGoalRequest(
                "member-xyz", "Goal B", BigDecimal.valueOf(200), BigDecimal.valueOf(20), "USD", null, "s2"
            ))));

        mockMvc.perform(get("/api/v1/savings/members/member-xyz/goals"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].name").value("Goal A"))
            .andExpect(jsonPath("$[1].name").value("Goal B"));
    }

    static class DummyEventOutbox implements EventOutbox {
        final List<Object> events = new ArrayList<>();
        @Override
        public void publish(Object event) {
            events.add(event);
        }
    }
}
