package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.application.usecases.RepaymentService.Receipt;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A higher credit record level raises the loan limit the member sees and is sized against, in plain words and
 * without a score. The record is fed by the real events (savings deposits, lending's schedule and repayments) or,
 * for a history Seed did not see happen, by facts written straight to the record.
 */
class RecordLevelEligibilityIT extends IntegrationTest {

    @Autowired
    MicroloanApplicationService microloans;
    @Autowired
    CreditRecordFacade record;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase deposits;
    @Autowired
    RepaymentService repayments;
    @Autowired
    FairLoanCheckService fairCheck;

    private UUID goalFor(UUID member) {
        return createGoal.execute(new CreateGoalUseCase.Command(member.toString(), "Mixer", Money.usd(1200), Money.usd(75), null,
            "sub-" + member)).getId();
    }

    private void saveOn(UUID member, UUID goalId, LocalDate on, String capture) {
        clock.set(on);
        deposits.execute(new RecordDepositUseCase.Command(goalId, Money.usd(75), capture + "-" + member));
        settle();
    }

    private void pay(LoanSummary loan, LocalDate on, String txn) {
        clock.set(on);
        repayments.recordReceipt(new Receipt(loan.loanId(), txn + "-" + loan.loanId(), Source.CHECKOUT, 10_000, 0, Instant.now(), null, "it"));
        settle();
    }

    private JsonNode eligibility(UUID member) throws Exception {
        return json(mvc.perform(get("/api/v1/loans/eligibility").header("X-Seed-Member-Id", member.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode json(String body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
    }

    @Test
    void aMemberWhoRepaidTheirFirstLoanSeesAHigherLimitOnTheSecond() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID goal = goalFor(ana);
        saveOn(ana, goal, LocalDate.of(2026, 11, 10), "c1");
        saveOn(ana, goal, LocalDate.of(2026, 12, 10), "c2");
        saveOn(ana, goal, LocalDate.of(2027, 1, 10), "c3");

        JsonNode first = eligibility(ana);
        assertThat(first.path("eligible").asBoolean()).isTrue();
        assertThat(first.path("tierLimitCents").asLong()).isEqualTo(150_000);
        assertThat(first.path("limitReason").asText()).contains("$1500.00").doesNotContainIgnoringCase("score");
        assertThat(first.path("recordLevel").asText()).isIn("NEW", "BUILDING");

        LoanSummary loan = disburse(ana, 300, 3, 9, LocalDate.of(2027, 1, 11));
        settle();
        pay(loan, LocalDate.of(2027, 2, 9), "T1");
        pay(loan, LocalDate.of(2027, 3, 9), "T2");
        pay(loan, LocalDate.of(2027, 4, 9), "T3");
        saveOn(ana, goal, LocalDate.of(2027, 2, 10), "c4");
        saveOn(ana, goal, LocalDate.of(2027, 3, 10), "c5");
        saveOn(ana, goal, LocalDate.of(2027, 4, 10), "c6");

        JsonNode second = eligibility(ana);
        assertThat(second.path("eligible").asBoolean()).isTrue();
        assertThat(second.path("recordLevel").asText()).isEqualTo("STEADY");
        assertThat(second.path("tierLimitCents").asLong()).isEqualTo(300_000).isGreaterThan(first.path("tierLimitCents").asLong());
        assertThat(second.path("completedLoansOnTime").asInt()).isEqualTo(1);
        assertThat(second.path("limitReason").asText()).contains("Steady").contains("$3000.00").doesNotContainIgnoringCase("score");
    }

    @Test
    void aRecordBuiltElsewhereLiftsTheLimitAboveTheLadderAndTheSizingAllowsIt() throws Exception {
        UUID ben = UUID.randomUUID();
        // January is real saving (its events reach the record), the rest of the year was saved before Seed saw it
        clock.set(LocalDate.of(2026, 1, 5));
        UUID goal = goalFor(ben);
        for (int deposit = 0; deposit < 4; deposit++) {
            deposits.execute(new RecordDepositUseCase.Command(goal, Money.usd(75), "c" + deposit + "-" + ben));
        }
        settle();
        fairCheck.grantConsent(ben);
        for (int month = 2; month <= 12; month++) {
            record.ingest(RecordFact.savingsDeposit("seed-dep-" + month + "-" + ben, ben, LocalDate.of(2026, month, 5)));
        }
        for (int n = 1; n <= 3; n++) {
            UUID priorLoan = UUID.randomUUID();
            record.ingest(RecordFact.loanDisbursed("seed-loan-" + n + "-" + ben, ben, priorLoan, LocalDate.of(2026, 1, 5)));
            record.ingest(RecordFact.instalmentPaid(ben, priorLoan, 1, 0, LocalDate.of(2026, 2, 5)));
            record.ingest(RecordFact.loanRepaid(ben, priorLoan, 0, LocalDate.of(2026, 2, 5)));
        }
        assertThat(record.levelOf(ben).level()).as(record.record(ben).toString()).isEqualTo("EXEMPLARY");
        clock.set(LocalDate.of(2027, 6, 1));

        JsonNode view = eligibility(ben);

        assertThat(view.path("recordLevel").asText()).isEqualTo("EXEMPLARY");
        assertThat(view.path("tierLimitCents").asLong()).isEqualTo(750_000);
        assertThat(view.path("completedLoansOnTime").asInt()).as("lending itself saw no repaid loan").isZero();
        assertThat(view.path("limitReason").asText()).isEqualTo("Your record is at Exemplary, which lifts your limit from $1500.00 to $7500.00.");
        assertThat(view.path("maxTermMonths").asInt()).isEqualTo(18);
        assertThat(view.path("reasons").toString()).doesNotContainIgnoringCase("score");

        // 18 months is outside the first rung's terms; the record's level makes it available
        LoanApplicationView application = microloans.submitApplication(ben,
            new SubmitApplicationCommand("Oven", 250_000L, 18, 15, WalletType.PAYPAL, true, OptionalLong.of(300_000L)));
        assertThat(application.status()).isEqualTo("SUBMITTED");
        assertThat(application.termMonths()).isEqualTo(18);
        settle();
    }

    @Test
    void aNewMemberSeesTheSameLimitTheLadderAlwaysGave() throws Exception {
        UUID cy = UUID.randomUUID();
        clock.set(LocalDate.of(2027, 6, 1));
        UUID goal = goalFor(cy);
        for (int month = 0; month < 3; month++) {
            deposits.execute(new RecordDepositUseCase.Command(goal, Money.usd(75), "c" + month + "-" + cy));
        }

        JsonNode view = eligibility(cy);

        assertThat(view.path("recordLevel").asText()).isEqualTo("NEW");
        assertThat(view.path("tierLimitCents").asLong()).isEqualTo(150_000);
        assertThat(view.path("maxTermMonths").asInt()).isEqualTo(12);
        assertThat(view.path("limitReason").asText()).isEqualTo("Your limit is $1500.00. It grows as you save and repay on time.");
        settle();
    }
}
