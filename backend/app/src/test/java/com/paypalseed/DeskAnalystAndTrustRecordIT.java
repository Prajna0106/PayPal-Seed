package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.application.usecases.RepaymentService.Receipt;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end integration test of the two narrated endpoints, with every figure read from real data:
 * 1. GET /api/v1/desk/analyst/briefing (Desk Analyst Agent): portfolio, queues and fund from the desk read models and
 *    the reserve facade.
 * 2. GET /api/v1/members/me/trust-record (Trust Agent): the signed-in member's own record, savings and repayments.
 */
class DeskAnalystAndTrustRecordIT extends IntegrationTest {

    private static final String MEMBER = "X-Seed-Member-Id";

    @Autowired
    ObjectMapper json;
    @Autowired
    ReserveFacade reserve;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase deposits;
    @Autowired
    RepaymentService repayments;
    @Autowired
    TransactionTemplate tx;

    private UUID staffId;

    @BeforeEach
    void setup() {
        staffId = UUID.randomUUID();
        clock.set(LocalDate.of(2026, 11, 2));
    }

    @Test
    void deskAnalystBriefingRequiresStaffOfficerRole() throws Exception {
        // Without staff header -> 401
        mvc.perform(get("/api/v1/desk/analyst/briefing"))
            .andExpect(status().isUnauthorized());

        // As a member -> not staff
        mvc.perform(get("/api/v1/desk/analyst/briefing").header(MEMBER, UUID.randomUUID().toString()))
            .andExpect(status().is4xxClientError());

        // As an officer -> 200 with the full briefing
        JsonNode briefing = briefing();
        assertThat(briefing.path("businessDate").asText()).isEqualTo("2026-11-02");
        assertThat(briefing.path("executiveSummary").asText()).isNotBlank();
        assertThat(briefing.path("queuePriorities").isArray()).isTrue();
        assertThat(briefing.path("riskObservations").isArray()).isTrue();
        assertThat(briefing.path("recommendedStaffingFocus").asText()).isNotBlank();
    }

    @Test
    void theBriefingFiguresFollowTheLoansAndTheFundThatReallyExist() throws Exception {
        JsonNode before = briefing();

        UUID ana = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        LoanSummary anaLoan = disburse(ana, 1_000, 6, 9, LocalDate.of(2026, 11, 2));
        LoanSummary bobLoan = disburse(bob, 500, 3, 15, LocalDate.of(2026, 11, 2));
        tx.executeWithoutResult(s -> reserve.recordDonation("CAP-brief-" + UUID.randomUUID(), 25_000, null, true));
        clock.set(LocalDate.of(2026, 11, 2));
        settle();

        JsonNode after = briefing();

        assertThat(after.path("activeLoansCount").asInt()).isEqualTo(before.path("activeLoansCount").asInt() + 2);
        assertThat(after.path("healthyLoansCount").asInt()).isEqualTo(before.path("healthyLoansCount").asInt() + 2);
        assertThat(after.path("atRiskLoansCount").asInt()).isEqualTo(before.path("atRiskLoansCount").asInt());
        assertThat(after.path("totalOutstandingCents").asLong()).isEqualTo(before.path("totalOutstandingCents").asLong()
            + anaLoan.outstandingCents() + bobLoan.outstandingCents());
        assertThat(after.path("reserveBalanceCents").asLong()).isEqualTo(before.path("reserveBalanceCents").asLong() + 25_000);
        // Nothing of the old fixed figures remains.
        assertThat(after.path("activeLoansCount").asInt()).isNotEqualTo(142);
        assertThat(after.path("totalOutstandingCents").asLong()).isNotEqualTo(17_040_000L);
        assertThat(after.path("reserveBalanceCents").asLong()).isNotEqualTo(3_500_000L);
    }

    @Test
    void trustRecordRequiresMemberAuth() throws Exception {
        mvc.perform(get("/api/v1/members/me/trust-record"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void twoMembersGetTheirOwnTrustRecordsAndNeverEachOthers() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID goal = tx.execute(s -> createGoal.execute(new CreateGoalUseCase.Command(ana.toString(), "Mixer",
            Money.usd(1200.00), Money.usd(75.00), LocalDate.of(2027, 6, 1), "sub-" + ana)).getId());
        saveOn(ana, goal, LocalDate.of(2026, 11, 10), "c1");
        LoanSummary loan = disburse(ana, 300, 3, 9, LocalDate.of(2026, 11, 10));
        settle();
        pay(loan, LocalDate.of(2026, 12, 9), "T1");
        saveOn(ana, goal, LocalDate.of(2026, 12, 10), "c2");
        pay(loan, LocalDate.of(2027, 1, 9), "T2");
        saveOn(ana, goal, LocalDate.of(2027, 1, 10), "c3");

        JsonNode anas = trustRecord(ana);
        JsonNode bobs = trustRecord(bob);

        assertThat(anas.path("memberId").asText()).isEqualTo(ana.toString());
        assertThat(anas.path("currentTier").asText()).isEqualTo("BUILDING");
        assertThat(anas.path("currentLimitCents").asLong()).isEqualTo(150_000);
        assertThat(anas.path("nextTier").asText()).isEqualTo("STEADY");
        assertThat(anas.path("nextLimitCents").asLong()).isEqualTo(300_000);
        assertThat(anas.path("onTimeRepaymentsCount").asInt()).isEqualTo(2);
        assertThat(anas.path("streakMonths").asInt()).isEqualTo(3);
        assertThat(anas.path("totalSavedCents").asLong()).isEqualTo(22_500);
        assertThat(anas.path("totalRepaidCents").asLong()).isEqualTo(20_000);
        assertThat(anas.path("summaryEn").asText()).isNotBlank();
        assertThat(anas.path("summaryEs").asText()).isNotBlank();
        assertThat(anas.path("certificateAttestation").asText()).isNotBlank();
        assertThat(anas.path("milestoneAdvice").asText()).isNotBlank();
        assertThat(anas.path("verificationHash").asText()).hasSize(64);

        assertThat(bobs.path("memberId").asText()).isEqualTo(bob.toString());
        assertThat(bobs.path("currentTier").asText()).isEqualTo("NEW");
        assertThat(bobs.path("currentLimitCents").asLong()).isZero();
        assertThat(bobs.path("nextTier").asText()).isEqualTo("BUILDING");
        assertThat(bobs.path("onTimeRepaymentsCount").asInt()).isZero();
        assertThat(bobs.path("streakMonths").asInt()).isZero();
        assertThat(bobs.path("totalSavedCents").asLong()).isZero();
        assertThat(bobs.path("totalRepaidCents").asLong()).isZero();
        assertThat(bobs.path("verificationHash").asText()).isNotEqualTo(anas.path("verificationHash").asText());
        assertThat(bobs.toString()).doesNotContain(ana.toString());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private JsonNode briefing() throws Exception {
        String body = mvc.perform(get("/api/v1/desk/analyst/briefing")
                .header("X-Seed-Staff-Id", staffId.toString())
                .header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode trustRecord(UUID member) throws Exception {
        String body = mvc.perform(get("/api/v1/members/me/trust-record").header(MEMBER, member.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private void saveOn(UUID member, UUID goalId, LocalDate on, String capture) {
        clock.set(on);
        deposits.execute(new RecordDepositUseCase.Command(goalId, Money.usd(75.00), capture + "-" + member));
        settle();
    }

    private void pay(LoanSummary loan, LocalDate on, String txn) {
        clock.set(on);
        repayments.recordReceipt(new Receipt(loan.loanId(), txn + "-" + loan.loanId(), Source.CHECKOUT, 10_000, 0,
            Instant.now(), null, "it"));
        settle();
    }
}
