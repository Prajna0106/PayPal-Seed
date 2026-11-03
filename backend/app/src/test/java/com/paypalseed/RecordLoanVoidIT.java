package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.application.usecases.RepaymentService.Receipt;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * A loan whose disbursement payout came back is void (ADR 0024) and leaves the member's credit record whole (ADR 0025,
 * follow-up): no loan, no instalments, no on-plan count and no level it lifted. The facts stay in the append-only store.
 */
@Import(IntegrationTest.Fakes.class)
class RecordLoanVoidIT extends MoneyFlowSupport {

    private static final UUID OFFICER_ONE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final UUID OFFICER_TWO = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");

    @Autowired
    MicroloanApplicationService applications;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase deposits;
    @Autowired
    FairLoanCheckService fairCheck;
    @Autowired
    CreditRecordFacade creditRecord;
    @Autowired
    RepaymentService repayments;

    @Test
    void aVoidedLoanWithPaymentsMadeBeforeTheReturnDisappearsFromTheRecord() throws Exception {
        UUID member = newMember();
        UUID application = approvedApplication(member);
        UUID loanId = registeredLoan(application);
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-REC-VOID-1", loanId, 30_000));
        settle();

        JsonNode live = recordOf(member);
        assertThat(live.at("/loans/disbursed").asInt()).isEqualTo(1);
        assertThat(live.at("/instalments/onTime").asInt()).as("the two instalments the 30,000 paid").isEqualTo(2);
        assertThat(kinds(member)).contains("LOAN_DISBURSED", "INSTALMENT_PAID");

        payoutReturnedWebhook(paypal.payoutOfReference(application.toString()));
        settle();

        JsonNode view = recordOf(member);
        assertThat(view.at("/loans/disbursed").asInt()).isZero();
        assertThat(view.at("/loans/repaidOnPlan").asInt()).isZero();
        assertThat(view.at("/loans/repaidOffPlan").asInt()).isZero();
        assertThat(view.at("/instalments/onTime").asInt()).isZero();
        assertThat(view.at("/instalments/late").asInt()).isZero();
        assertThat(view.at("/instalments/openLate").asInt()).isZero();
        assertThat(view.path("defaults").asInt()).isZero();
        assertThat(view.at("/savings/onTimeMonths").asInt()).as("the saving is the member's own and stays").isEqualTo(1);
        assertThat(creditRecord.history(member, null, 50).items()).extracting(i -> i.kind()).containsOnly("SAVINGS_DEPOSIT");

        // The facts are append-only: they stay stored, and a replayed return or a late fact of the dead loan changes nothing.
        assertThat(kinds(member)).contains("LOAN_DISBURSED", "INSTALMENT_PAID", "LOAN_VOIDED");
        int facts = kinds(member).size();
        payoutReturnedWebhook(paypal.payoutOfReference(application.toString()));
        settle();
        creditRecord.ingest(RecordFact.instalmentPaid(member, loanId, 3, 0, LocalDate.of(2026, 12, 15)));
        creditRecord.ingest(RecordFact.loanDefaulted(member, loanId, LocalDate.of(2027, 3, 1)));
        assertThat(kinds(member)).hasSize(facts + 2);
        JsonNode again = recordOf(member);
        assertThat(again.at("/instalments/onTime").asInt()).isZero();
        assertThat(again.path("defaults").asInt()).isZero();
        assertThat(again.at("/loans/disbursed").asInt()).isZero();
    }

    @Test
    void aLevelTheVoidedLoanLiftedIsCorrected() throws Exception {
        UUID member = newMember();
        // June to October here and November from the savings deposits below: six months in a row, so the loan decides STEADY.
        for (int month = 6; month <= 10; month++) {
            creditRecord.ingest(RecordFact.savingsDeposit("void-it-" + member + "-" + month, member, LocalDate.of(2026, month, 5)));
        }
        UUID application = approvedApplication(member);
        UUID loanId = registeredLoan(application);
        repayments.recordReceipt(new Receipt(loanId, "own-" + loanId, Source.CHECKOUT, 120_000, 0, Instant.now(), null, "it"));
        settle();

        JsonNode lifted = recordOf(member);
        assertThat(lifted.at("/loans/repaidOnPlan").asInt()).isEqualTo(1);
        assertThat(lifted.path("level").asText()).isEqualTo("STEADY");

        payoutReturnedWebhook(paypal.payoutOfReference(application.toString()));
        settle();

        JsonNode corrected = recordOf(member);
        assertThat(corrected.path("level").asText()).as("the loan never was, so nor was the rise").isEqualTo("BUILDING");
        assertThat(corrected.at("/loans/disbursed").asInt()).isZero();
        assertThat(corrected.at("/loans/repaidOnPlan").asInt()).isZero();
        assertThat(corrected.at("/instalments/onTime").asInt()).isZero();
        assertThat(corrected.at("/savings/onTimeMonths").asInt()).isEqualTo(6);
        assertThat(creditRecord.levelOf(member).level()).isEqualTo("BUILDING");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private UUID approvedApplication(UUID member) {
        clock.set(LocalDate.of(2026, 11, 10));
        SavingsGoal goal = createGoal.execute(new CreateGoalUseCase.Command(member.toString(), "Mixer", Money.usd(1200), Money.usd(75),
                null, "sub-" + member));
        for (int i = 1; i <= 4; i++) {
            deposits.execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(75), "cap-" + i + "-" + member));
        }
        fairCheck.grantConsent(member);
        var submitted = applications.submitApplication(member,
                new SubmitApplicationCommand("Commercial mixer", 120_000L, 8, 15, WalletType.PAYPAL, true, OptionalLong.of(160_000L)));
        applications.approveApplication(submitted.id(), OFFICER_ONE);
        return applications.approveApplication(submitted.id(), OFFICER_TWO).id();
    }

    /** Asks PayPal to pay, has PayPal confirm it, and returns the loan that registers. */
    private UUID registeredLoan(UUID applicationId) throws Exception {
        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", applicationId), OFFICER_TWO, "OFFICER"))
                .andExpect(status().isOk());
        payoutSucceededWebhook(paypal.payoutOfReference(applicationId.toString()));
        settle();
        return jdbc.sql("SELECT id FROM lending.loan WHERE disbursement_ref = ?").param("loan-" + applicationId + "-disb")
                .query(UUID.class).single();
    }

    private JsonNode recordOf(UUID member) throws Exception {
        return read(mvc.perform(get("/api/v1/members/me/record").header("X-Seed-Member-Id", member.toString()))
                .andExpect(status().isOk()).andReturn());
    }

    private List<String> kinds(UUID member) {
        return jdbc.sql("SELECT kind FROM creditrecord.record_fact WHERE member_id = ?").param(member).query(String.class).list();
    }
}
