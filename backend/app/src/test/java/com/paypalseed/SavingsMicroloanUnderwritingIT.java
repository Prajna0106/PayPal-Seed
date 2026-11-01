package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end PostgreSQL + Flyway + jOOQ integration test covering:
 * - Feature 1: Goal Savings (`savings.savings_goal`)
 * - Feature 2: Savings-Linked Microloans (`lending.loan_application` -> `lending.loan`)
 * - Feature 3: The Fair Loan Check (`underwriting.income_consent` & `underwriting.fair_loan_check_assessment`)
 */
class SavingsMicroloanUnderwritingIT extends IntegrationTest {

    @Autowired
    CreateGoalUseCase createGoalUseCase;

    @Autowired
    RecordDepositUseCase recordDepositUseCase;

    @Autowired
    SavingsFacade savingsFacade;

    @Autowired
    FairLoanCheckService fairLoanCheckService;

    @Autowired
    MicroloanApplicationService microloanService;

    @Test
    void persistsSavingsGoalsFairLoanCheckAndMicroloanApplicationInPostgres() {
        UUID anaId = UUID.randomUUID();
        UUID officer1 = UUID.randomUUID();
        UUID officer2 = UUID.randomUUID();

        // 1. F1: Create savings goal and record 4 monthly deposits ($75 x 4 = $300)
        SavingsGoal goal = createGoalUseCase.execute(new CreateGoalUseCase.Command(
            anaId.toString(),
            "Commercial Stand Mixer Goal",
            Money.usd(1200.00),
            Money.usd(75.00),
            LocalDate.of(2027, 6, 1),
            "sub-ana-" + anaId
        ));

        for (int i = 1; i <= 4; i++) {
            recordDepositUseCase.execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(75.00), "cap-" + i));
        }

        Integer goalRows = jdbc.sql("SELECT COUNT(*) FROM savings.savings_goal WHERE id = :id AND deposit_count = 4 AND current_balance_cents = 30000")
            .param("id", goal.getId())
            .query(Integer.class)
            .single();
        assertThat(goalRows).isEqualTo(1);

        MemberSavingsSummary summary = savingsFacade.getMemberSavingsSummary(anaId.toString());
        assertThat(summary.currentStreakMonths()).isEqualTo(4);
        assertThat(summary.totalSavedBalanceCents()).isEqualTo(30_000L);
        assertThat(summary.microloanEligible()).isTrue();

        // 2. F3: Grant consent, run Fair Loan Check, flag member dispute, and record officer decision
        fairLoanCheckService.grantConsent(anaId);

        FairCheckCardView card = fairLoanCheckService.runFairLoanCheck(anaId, new RunFairCheckCommand(
            null,
            "Commercial stand mixer for home bakery",
            120_000L,
            8,
            List.of(
                new IncomeMonthDto("2026-05", 165_000L),
                new IncomeMonthDto("2026-06", 172_000L),
                new IncomeMonthDto("2026-07", 118_000L),
                new IncomeMonthDto("2026-08", 165_000L),
                new IncomeMonthDto("2026-09", 170_000L),
                new IncomeMonthDto("2026-10", 170_000L)
            ),
            160_000L,
            90_000L,
            0,
            false
        ));

        assertThat(card.recommended()).isTrue();
        assertThat(card.instalmentCapCents()).isEqualTo(15_000L);

        fairLoanCheckService.disputeAssessment(card.assessmentId(), anaId, "July was a two-week vacation closure.");
        FairCheckCardView decidedCard = fairLoanCheckService.recordOfficerDecision(
            card.assessmentId(),
            officer1,
            OfficerDecisionType.APPROVED,
            null,
            null,
            "Verified 4/4 savings deposits and steady bakery revenue."
        );
        assertThat(decidedCard.officerDecision()).isEqualTo("APPROVED");
        assertThat(decidedCard.memberDisputed()).isTrue();

        Integer assessmentRows = jdbc.sql(
                "SELECT COUNT(*) FROM underwriting.fair_loan_check_assessment WHERE id = :id AND officer_decision = 'APPROVED' AND member_disputed = true")
            .param("id", card.assessmentId())
            .query(Integer.class)
            .single();
        assertThat(assessmentRows).isEqualTo(1);

        // 3. F2: Check eligibility, submit $1,200 / 8-month application, two-officer maker-checker approval, and disburse
        EligibilityView eligibility = microloanService.checkEligibility(anaId, OptionalLong.of(160_000L));
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.tier()).isEqualTo("LEVEL_1");

        LoanApplicationView submitted = microloanService.submitApplication(anaId, new SubmitApplicationCommand(
            "Commercial stand mixer for home bakery",
            120_000L,
            8,
            15,
            WalletType.VENMO,
            true,
            OptionalLong.of(160_000L)
        ));
        assertThat(submitted.status()).isEqualTo("SUBMITTED");

        microloanService.approveApplication(submitted.id(), officer1);
        microloanService.approveApplication(submitted.id(), officer2);
        LoanApplicationView disbursed = microloanService.disburseApplication(submitted.id());

        assertThat(disbursed.status()).isEqualTo("DISBURSED");
        assertThat(disbursed.loanId()).isNotNull();

        Integer appRows = jdbc.sql(
                "SELECT COUNT(*) FROM lending.loan_application WHERE id = :id AND status = 'DISBURSED' AND loan_id = :loanId")
            .param("id", submitted.id())
            .param("loanId", disbursed.loanId())
            .query(Integer.class)
            .single();
        assertThat(appRows).isEqualTo(1);
    }
}
