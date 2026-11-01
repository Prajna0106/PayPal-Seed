package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.events.LoanApplicationChanged;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.FakePayPal.PayoutOutcome;
import com.paypalseed.support.FakePayPal.SimPayout;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The loan disbursement payout saga over HTTP and webhooks: the desk asks, PayPal pays, and only then is the loan
 * registered, once. A failed or returned payout sends the application back to APPROVED with a reason, and nothing
 * can cancel an application whose money is on its way.
 */
@Import({IntegrationTest.Fakes.class, DisbursementPayoutIT.Recording.class})
class DisbursementPayoutIT extends MoneyFlowSupport {

    public static class EventLog {
        final List<LoanApplicationChanged> changes = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        public void on(LoanApplicationChanged e) {
            changes.add(e);
        }

        List<String> statuses(UUID applicationId) {
            return changes.stream().filter(c -> c.applicationId().equals(applicationId)).map(LoanApplicationChanged::status).toList();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {
        @Bean
        EventLog eventLog() {
            return new EventLog();
        }
    }

    @Autowired
    MicroloanApplicationService applications;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase deposits;
    @Autowired
    FairLoanCheckService fairCheck;
    @Autowired
    EventLog events;

    private static final UUID OFFICER_ONE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final UUID OFFICER_TWO = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");

    /** Four months of saving, then an application for $1,200 over 8 months that two officers approved. */
    private LoanApplicationView approvedApplication(UUID member) {
        return approvedApplication(member, WalletType.PAYPAL);
    }

    private LoanApplicationView approvedApplication(UUID member, WalletType wallet) {
        clock.set(LocalDate.of(2026, 11, 10));
        SavingsGoal goal = createGoal.execute(new CreateGoalUseCase.Command(member.toString(), "Mixer", Money.usd(1200), Money.usd(75),
            null, "sub-" + member));
        for (int i = 1; i <= 4; i++) {
            deposits.execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(75), "cap-" + i + "-" + member));
        }
        fairCheck.grantConsent(member);
        LoanApplicationView submitted = applications.submitApplication(member,
            new SubmitApplicationCommand("Commercial mixer", 120_000L, 8, 15, wallet, true, OptionalLong.of(160_000L)));
        applications.approveApplication(submitted.id(), OFFICER_ONE);
        return applications.approveApplication(submitted.id(), OFFICER_TWO);
    }

    private JsonNode disburse(UUID applicationId, int expectedStatus) throws Exception {
        return read(mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", applicationId), OFFICER_TWO, "OFFICER"))
            .andExpect(status().is(expectedStatus)).andReturn());
    }

    private int loansRegistered(UUID applicationId) {
        return jdbc.sql("SELECT COUNT(*) FROM lending.loan WHERE disbursement_ref = ?").param("loan-" + applicationId + "-disb")
            .query(Integer.class).single();
    }

    private JsonNode application(UUID member, UUID applicationId) throws Exception {
        return read(mvc.perform(get("/api/v1/loans/applications/{id}", applicationId).header("X-Seed-Member-Id", member.toString()))
            .andExpect(status().isOk()).andReturn());
    }

    @Test
    void theLoanIsRegisteredOnlyWhenPayPalConfirmsThePayoutAndOnlyOnce() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member).id();
        long cashBefore = programCash();

        JsonNode asked = disburse(id, 200);

        assertThat(asked.path("status").asText()).isEqualTo("DISBURSING");
        assertThat(asked.path("loanId").isNull() || asked.path("loanId").isMissingNode()).isTrue();
        SimPayout payout = paypal.payoutOfReference(id.toString());
        assertThat(payout.amountCents).isEqualTo(120_000);
        assertThat(payout.receiver).isEqualTo(payerIdOf(member));
        assertThat(payout.recipientType).isEqualTo("PAYPAL_ID");
        assertThat(payout.wallet).as("the application chose a PayPal wallet").isEqualTo("PAYPAL");
        assertThat(loansRegistered(id)).as("no loan while the money is on its way").isZero();
        assertThat(programCash()).isEqualTo(cashBefore);

        payoutSucceededWebhook(payout);
        settle();

        JsonNode done = application(member, id);
        assertThat(done.path("status").asText()).isEqualTo("DISBURSED");
        UUID loanId = UUID.fromString(done.path("loanId").asText());
        assertThat(done.path("disbursementRef").asText()).isEqualTo("loan-" + id + "-disb");
        assertThat(done.path("coolingOffExpiresAt").asText()).isNotBlank();
        assertThat(loansRegistered(id)).isEqualTo(1);
        assertThat(receivable(loanId)).isEqualTo(120_000);
        assertThat(programCash()).isEqualTo(cashBefore - 120_000);
        assertThat(queries.schedule(queries.loan(loanId), java.util.Optional.empty()).instalments()).hasSize(8);
        assertIntegrity(loanId);
        assertLedgerBalanced();
        assertThat(events.statuses(id)).containsExactly("SUBMITTED", "PENDING_SECOND_APPROVAL", "APPROVED", "DISBURSING", "DISBURSED");

        // the same success reported again, and the desk pressing the button again, change nothing
        payoutSucceededWebhook(payout);
        settle();
        JsonNode replay = disburse(id, 200);
        assertThat(replay.path("status").asText()).isEqualTo("DISBURSED");
        assertThat(replay.path("loanId").asText()).isEqualTo(loanId.toString());
        assertThat(loansRegistered(id)).isEqualTo(1);
        assertThat(receivable(loanId)).isEqualTo(120_000);
        assertThat(paypal.payoutsByBatch.values().stream().filter(p -> p.senderItemId.equals(id.toString()))).hasSize(1);
    }

    @Test
    void aVenmoApplicationIsPaidToTheVenmoWalletByEmail() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member, WalletType.VENMO).id();

        disburse(id, 200);

        SimPayout payout = paypal.payoutOfReference(id.toString());
        assertThat(payout.wallet).isEqualTo("VENMO");
        assertThat(payout.recipientType).isEqualTo("EMAIL");
        assertThat(payout.receiver).isEqualTo("ana@example.test");
        settle();
    }

    @Test
    void aPayoutPayPalAnswersAtOnceRegistersTheLoanInTheSameRequest() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member).id();
        paypal.payoutOutcome = PayoutOutcome.INSTANT_SUCCESS;

        JsonNode done = disburse(id, 200);

        assertThat(done.path("status").asText()).isEqualTo("DISBURSED");
        assertThat(done.path("loanId").asText()).isNotBlank();
        settle();
        assertThat(loansRegistered(id)).as("the payout event finds it done").isEqualTo(1);
        assertLedgerBalanced();
    }

    @Test
    void aFailedPayoutReturnsTheApplicationToApprovedWithAReasonAndALaterRetryPaysWithANewReference() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member).id();
        disburse(id, 200);
        SimPayout payout = paypal.payoutOfReference(id.toString());

        payoutFailedWebhook(payout, "RECEIVER_UNREGISTERED");
        settle();

        JsonNode failed = application(member, id);
        assertThat(failed.path("status").asText()).isEqualTo("APPROVED");
        assertThat(failed.path("payoutIssue").asText()).isEqualTo("RECEIVER_UNREGISTERED");
        assertThat(failed.path("loanId").isNull() || failed.path("loanId").isMissingNode()).isTrue();
        assertThat(loansRegistered(id)).isZero();
        assertThat(events.statuses(id)).endsWith("APPROVED", "DISBURSING", "APPROVED");

        paypal.payoutOutcome = PayoutOutcome.INSTANT_SUCCESS;
        JsonNode retried = disburse(id, 200);

        assertThat(retried.path("status").asText()).isEqualTo("DISBURSED");
        assertThat(retried.path("payoutIssue").isNull() || retried.path("payoutIssue").isMissingNode()).isTrue();
        assertThat(paypal.payoutOfReference(id + "-a2").amountCents).isEqualTo(120_000);
        assertThat(loansRegistered(id)).isEqualTo(1);
        settle();
        assertThat(loansRegistered(id)).isEqualTo(1);
        assertLedgerBalanced();
    }

    @Test
    void aRefusalInTheCreateResponseIsHandledInTheSameRequest() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member).id();
        paypal.payoutOutcome = PayoutOutcome.FAILED;

        JsonNode refused = disburse(id, 200);

        assertThat(refused.path("status").asText()).isEqualTo("APPROVED");
        assertThat(refused.path("payoutIssue").asText()).isEqualTo("RECEIVER_UNREGISTERED");
        settle();
        assertThat(loansRegistered(id)).isZero();
    }

    @Test
    void anUnclaimedPayoutKeepsTheApplicationDisbursingUntilItIsClaimedOrReturned() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member).id();
        paypal.payoutOutcome = PayoutOutcome.UNCLAIMED;

        JsonNode asked = disburse(id, 200);
        settle();

        assertThat(asked.path("status").asText()).isEqualTo("DISBURSING");
        assertThat(application(member, id).path("status").asText()).isEqualTo("DISBURSING");
        assertThat(application(member, id).path("payoutIssue").asText()).isEqualTo("UNCLAIMED");

        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        assertThat(application(member, id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(application(member, id).path("payoutIssue").asText()).isEqualTo("UNCLAIMED_EXPIRED");
        assertThat(loansRegistered(id)).isZero();
    }

    @Test
    void theMemberCannotCancelWhileTheMoneyIsOnItsWayButCanAfterItArrived() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member).id();
        disburse(id, 200);

        mvc.perform(post("/api/v1/loans/applications/{id}/cooling-off-cancel", id).header("X-Seed-Member-Id", member.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(Matchers.endsWith("application-invalid-state")));
        assertThat(application(member, id).path("status").asText()).isEqualTo("DISBURSING");

        payoutSucceededWebhook(paypal.payoutOfReference(id.toString()));
        settle();
        mvc.perform(post("/api/v1/loans/applications/{id}/cooling-off-cancel", id).header("X-Seed-Member-Id", member.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED_COOLING_OFF"));
        assertThat(events.statuses(id)).endsWith("DISBURSED", "CANCELLED_COOLING_OFF");
    }

    @Test
    void withoutAPayPalAccountOnFileNothingIsSentAndTheApplicationStaysApproved() throws Exception {
        UUID member = UUID.randomUUID(); // never signed up
        UUID id = approvedApplication(member).id();
        int payoutsBefore = paypal.payoutsByBatch.size();

        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", id), OFFICER_TWO, "OFFICER"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(Matchers.endsWith("payout-recipient-missing")));

        assertThat(application(member, id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(paypal.payoutsByBatch).hasSize(payoutsBefore);
    }

    @Test
    void aTransientPayPalFailureLeavesTheApplicationApprovedAndTheRetryIsTheSamePayout() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member).id();
        paypal.failNext("createPayout", FakePayPal.unavailable());

        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", id), OFFICER_TWO, "OFFICER"))
            .andExpect(status().isServiceUnavailable());
        assertThat(application(member, id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(events.statuses(id)).as("the rolled back DISBURSING was never announced").doesNotContain("DISBURSING");

        paypal.payoutOutcome = PayoutOutcome.INSTANT_SUCCESS;
        assertThat(disburse(id, 200).path("status").asText()).isEqualTo("DISBURSED");
        assertThat(paypal.payoutOfReference(id.toString()).amountCents).isEqualTo(120_000);
        settle();
        assertThat(loansRegistered(id)).isEqualTo(1);
    }

    @Test
    void onlyOfficersCanDisburseAndAnUnapprovedApplicationCannot() throws Exception {
        UUID member = newMember();
        LoanApplicationView approved = approvedApplication(member);
        UUID id = approved.id();

        mvc.perform(post("/api/v1/desk/loans/applications/{id}/disburse", id)).andExpect(status().isUnauthorized());
        mvc.perform(asMember(post("/api/v1/desk/loans/applications/{id}/disburse", id), member)).andExpect(status().isForbidden());
        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", id), OFFICER_TWO, "")).andExpect(status().isForbidden());
        assertThat(application(member, id).path("status").asText()).isEqualTo("APPROVED");

        UUID other = newMember();
        fairCheck.grantConsent(other);
        SavingsGoal goal = createGoal.execute(new CreateGoalUseCase.Command(other.toString(), "Oven", Money.usd(1200), Money.usd(75),
            null, "sub-" + other));
        for (int i = 1; i <= 4; i++) {
            deposits.execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(75), "cap-" + i + "-" + other));
        }
        UUID unapproved = applications.submitApplication(other,
            new SubmitApplicationCommand("Oven", 120_000L, 8, 15, WalletType.PAYPAL, true, OptionalLong.of(160_000L))).id();
        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", unapproved), OFFICER_TWO, "OFFICER"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(Matchers.endsWith("application-invalid-state")));
        settle();
    }

    @Test
    void theDeskSeesTheApplicationAndItsPayoutIssue() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member).id();
        paypal.payoutOutcome = PayoutOutcome.FAILED;
        disburse(id, 200);

        JsonNode list = read(mvc.perform(asStaff(get("/api/v1/desk/loans/applications"), OFFICER_TWO, "OFFICER")).andExpect(status().isOk()).andReturn());

        JsonNode row = null;
        for (JsonNode candidate : list) {
            if (candidate.path("id").asText().equals(id.toString())) {
                row = candidate;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("status").asText()).isEqualTo("APPROVED");
        assertThat(row.path("payoutIssue").asText()).isEqualTo("RECEIVER_UNREGISTERED");
        settle();
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_SAVINGS, member))).isEqualTo(30_000);
    }
}
