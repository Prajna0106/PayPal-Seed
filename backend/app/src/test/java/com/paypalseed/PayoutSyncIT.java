package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import com.paypalseed.payments.api.dto.PaymentsDtos.SendPayout;
import com.paypalseed.payments.api.events.PaymentEvents.PayoutFailed;
import com.paypalseed.payments.api.events.PaymentEvents.PayoutReturned;
import com.paypalseed.payments.api.events.PaymentEvents.PayoutSucceeded;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.FakePayPal.SimPayout;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A payout whose webhooks never came (a machine PayPal cannot call, or a lost delivery) is brought up to date by reading
 * PayPal (ADR 0029). The read goes through the step the webhooks use, so a webhook that comes before, after or twice changes
 * nothing, and every outcome is announced once however many reports carry it.
 */
@Import({IntegrationTest.Fakes.class, PayoutSyncIT.Recording.class})
class PayoutSyncIT extends MoneyFlowSupport {

    public static class EventLog {
        final List<PayoutSucceeded> succeeded = new CopyOnWriteArrayList<>();
        final List<PayoutFailed> failed = new CopyOnWriteArrayList<>();
        final List<PayoutReturned> returned = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(PayoutSucceeded e) {
            succeeded.add(e);
        }

        @EventListener
        public void on(PayoutFailed e) {
            failed.add(e);
        }

        @EventListener
        public void on(PayoutReturned e) {
            returned.add(e);
        }

        long count(String ref) {
            return succeeded.stream().filter(e -> e.referenceId().equals(ref)).count()
                + failed.stream().filter(e -> e.referenceId().equals(ref)).count()
                + returned.stream().filter(e -> e.referenceId().equals(ref)).count();
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
    PaymentsFacade payments;
    @Autowired
    EventLog events;

    private static final UUID OFFICER_ONE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final UUID OFFICER_TWO = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");

    /** Four months of saving, then an application for $1,200 over 8 months that two officers approved. */
    private LoanApplicationView approvedApplication(UUID member) {
        clock.set(LocalDate.of(2026, 11, 10));
        SavingsGoal goal = createGoal.execute(new CreateGoalUseCase.Command(member.toString(), "Mixer", Money.usd(1200), Money.usd(75),
            null, "sub-" + member));
        for (int i = 1; i <= 4; i++) {
            deposits.execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(75), "cap-" + i + "-" + member));
        }
        fairCheck.grantConsent(member);
        LoanApplicationView submitted = applications.submitApplication(member,
            new SubmitApplicationCommand("Commercial mixer", 120_000L, 8, 15, WalletType.PAYPAL, true, OptionalLong.of(160_000L)));
        applications.approveApplication(submitted.id(), OFFICER_ONE);
        return applications.approveApplication(submitted.id(), OFFICER_TWO);
    }

    /** A disbursement PayPal accepted but has not finished: the payout is PENDING at PayPal and in Seed. */
    private UUID pendingDisbursement() throws Exception {
        UUID id = approvedApplication(newMember()).id();
        JsonNode asked = read(mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", id), OFFICER_TWO, "OFFICER"))
            .andExpect(status().isOk()).andReturn());
        assertThat(asked.path("status").asText()).isEqualTo("DISBURSING");
        return id;
    }

    private MvcResult syncRequest(UUID applicationId, UUID staff, String role) throws Exception {
        return mvc.perform(asStaff(post("/api/v1/desk/applications/{id}/payout/sync", applicationId), staff, role)).andReturn();
    }

    private JsonNode sync(UUID applicationId) throws Exception {
        MvcResult result = syncRequest(applicationId, OFFICER_ONE, "OFFICER");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    /** The five second gap is real time; a test that reads twice lifts it by forgetting the last read. */
    private void forgetLastRead(UUID applicationId) {
        jdbc.sql("UPDATE payments.payout SET last_synced_at = NULL WHERE reference_id = ?").param(applicationId.toString()).update();
    }

    private int loansRegistered(UUID applicationId) {
        return jdbc.sql("SELECT COUNT(*) FROM lending.loan WHERE disbursement_ref = ?").param("loan-" + applicationId + "-disb")
            .query(Integer.class).single();
    }

    private int transitions(String reference, String to) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM payments.payout_transition t JOIN payments.payout p ON p.id = t.payout_id
                WHERE p.reference_id = ? AND t.to_status = ?""").param(reference).param(to).query(Integer.class).single();
    }

    private String sourceOf(String reference, String to) {
        return jdbc.sql("""
                SELECT t.source FROM payments.payout_transition t JOIN payments.payout p ON p.id = t.payout_id
                WHERE p.reference_id = ? AND t.to_status = ?""").param(reference).param(to).query(String.class).single();
    }

    private String payoutStatus(String reference) {
        return jdbc.sql("SELECT status FROM payments.payout WHERE reference_id = ?").param(reference).query(String.class).single();
    }

    // ---- the desk's "Check again" ----

    @Test
    void aPayoutPayPalPaidWithoutAnyWebhookRegistersTheLoanOnceAndTheNextCheckChangesNothing() throws Exception {
        UUID id = pendingDisbursement();
        String ref = id.toString();
        SimPayout payout = paypal.payoutOfReference(ref);
        assertThat(sync(id).path("status").asText()).as("PayPal still pending").isEqualTo("DISBURSING");
        assertThat(loansRegistered(id)).isZero();
        paypal.settlePayout(payout.itemId, "SUCCESS", null);
        forgetLastRead(id);

        JsonNode done = sync(id);

        assertThat(done.path("status").asText()).isEqualTo("DISBURSED");
        UUID loanId = UUID.fromString(done.path("loanId").asText());
        assertThat(loansRegistered(id)).isEqualTo(1);
        assertThat(receivable(loanId)).isEqualTo(120_000);
        assertThat(queries.schedule(queries.loan(loanId), java.util.Optional.empty()).instalments()).hasSize(8);
        assertThat(payoutStatus(ref)).isEqualTo("SUCCEEDED");
        assertThat(transitions(ref, "SUCCEEDED")).isEqualTo(1);
        assertThat(sourceOf(ref, "SUCCEEDED")).isEqualTo("POLL");
        assertThat(events.count(ref)).isEqualTo(1);
        settle();
        assertThat(loansRegistered(id)).as("the event the read published finds it done").isEqualTo(1);
        assertIntegrity(loanId);
        assertLedgerBalanced();

        long reads = paypal.callCount("getPayoutItem");
        forgetLastRead(id);
        JsonNode again = sync(id);
        assertThat(again.path("status").asText()).isEqualTo("DISBURSED");
        assertThat(again.path("loanId").asText()).isEqualTo(loanId.toString());
        assertThat(loansRegistered(id)).isEqualTo(1);
        assertThat(receivable(loanId)).isEqualTo(120_000);
        assertThat(transitions(ref, "SUCCEEDED")).isEqualTo(1);
        assertThat(events.count(ref)).isEqualTo(1);
        assertThat(paypal.callCount("getPayoutItem")).as("a delivered payout read again changes nothing").isGreaterThanOrEqualTo(reads);
    }

    @Test
    void aWebhookThatCameFirstIsNotAppliedAgainByTheCheck() throws Exception {
        UUID id = pendingDisbursement();
        String ref = id.toString();
        payoutSucceededWebhook(paypal.payoutOfReference(ref));
        settle();
        assertThat(loansRegistered(id)).isEqualTo(1);

        JsonNode checked = sync(id);

        assertThat(checked.path("status").asText()).isEqualTo("DISBURSED");
        assertThat(loansRegistered(id)).isEqualTo(1);
        assertThat(transitions(ref, "SUCCEEDED")).isEqualTo(1);
        assertThat(sourceOf(ref, "SUCCEEDED")).isEqualTo("WEBHOOK");
        assertThat(events.count(ref)).isEqualTo(1);
        settle();
        assertThat(loansRegistered(id)).isEqualTo(1);
        assertLedgerBalanced();
    }

    @Test
    void aWebhookThatComesAfterTheCheckChangesNothingMore() throws Exception {
        UUID id = pendingDisbursement();
        String ref = id.toString();
        SimPayout payout = paypal.payoutOfReference(ref);
        paypal.settlePayout(payout.itemId, "SUCCESS", null);
        assertThat(sync(id).path("status").asText()).isEqualTo("DISBURSED");

        payoutSucceededWebhook(payout);
        payoutSucceededWebhook(payout); // delivered twice
        settle();

        assertThat(loansRegistered(id)).isEqualTo(1);
        assertThat(transitions(ref, "SUCCEEDED")).isEqualTo(1);
        assertThat(events.count(ref)).isEqualTo(1);
        assertLedgerBalanced();
    }

    @Test
    void aPayoutPayPalFailedOrDeniedReturnsTheApplicationToApprovedWithTheReason() throws Exception {
        UUID failedId = pendingDisbursement();
        paypal.settlePayout(paypal.payoutOfReference(failedId.toString()).itemId, "FAILED", "RECEIVER_UNREGISTERED");

        JsonNode failed = sync(failedId);

        assertThat(failed.path("status").asText()).isEqualTo("APPROVED");
        assertThat(failed.path("payoutIssue").asText()).isEqualTo("RECEIVER_UNREGISTERED");
        assertThat(loansRegistered(failedId)).isZero();
        assertThat(payoutStatus(failedId.toString())).isEqualTo("FAILED");
        assertThat(events.failed.stream().filter(e -> e.referenceId().equals(failedId.toString()))).singleElement()
            .satisfies(e -> assertThat(e.terminal()).isTrue());
        settle();
        assertThat(events.count(failedId.toString())).isEqualTo(1);

        UUID deniedId = pendingDisbursement();
        SimPayout denied = paypal.payoutOfReference(deniedId.toString());
        paypal.settlePayout(denied.itemId, "DENIED", "ACCOUNT_RESTRICTED");
        denied.batchStatus = "DENIED";

        JsonNode refused = sync(deniedId);

        assertThat(refused.path("status").asText()).isEqualTo("APPROVED");
        assertThat(payoutStatus(deniedId.toString())).isEqualTo("FAILED");
        assertThat(refused.path("payoutIssue").asText()).isEqualTo("ACCOUNT_RESTRICTED");
        assertThat(events.count(deniedId.toString())).isEqualTo(1);
    }

    @Test
    void aPayoutPayPalReturnedBeforeTheLoanWasRegisteredFreesTheApplicationAndAfterwardsVoidsTheLoan() throws Exception {
        UUID early = pendingDisbursement();
        paypal.settlePayout(paypal.payoutOfReference(early.toString()).itemId, "RETURNED", null);

        JsonNode back = sync(early);

        assertThat(back.path("status").asText()).isEqualTo("APPROVED");
        assertThat(payoutStatus(early.toString())).isEqualTo("RETURNED");
        assertThat(loansRegistered(early)).isZero();
        assertThat(events.returned.stream().filter(e -> e.referenceId().equals(early.toString()))).hasSize(1);

        UUID late = pendingDisbursement();
        SimPayout payout = paypal.payoutOfReference(late.toString());
        paypal.settlePayout(payout.itemId, "SUCCESS", null);
        assertThat(sync(late).path("status").asText()).isEqualTo("DISBURSED");
        paypal.settlePayout(payout.itemId, "RETURNED", null);
        forgetLastRead(late);

        JsonNode voided = sync(late);

        assertThat(voided.path("status").asText()).isEqualTo("APPROVED");
        assertThat(voided.path("payoutIssue").asText()).isEqualTo("RETURNED_AFTER_DISBURSEMENT");
        assertThat(payoutStatus(late.toString())).isEqualTo("RETURNED");
        assertThat(events.count(late.toString())).isEqualTo(2);
        settle();
        assertThat(jdbc.sql("SELECT status FROM lending.loan WHERE disbursement_ref = ?").param("loan-" + late + "-disb")
            .query(String.class).single()).isEqualTo("DISBURSEMENT_RETURNED");
        assertLedgerBalanced();
    }

    @Test
    void aPayoutReadByItsBatchBeforeAnItemIdIsKnownLearnsTheItemId() throws Exception {
        UUID id = pendingDisbursement();
        String ref = id.toString();
        SimPayout payout = paypal.payoutOfReference(ref);
        jdbc.sql("UPDATE payments.payout SET paypal_item_id = NULL WHERE reference_id = ?").param(ref).update();
        paypal.settlePayout(payout.itemId, "SUCCESS", null);
        long batchReads = paypal.callCount("getPayoutBatch");

        assertThat(sync(id).path("status").asText()).isEqualTo("DISBURSED");

        assertThat(paypal.callCount("getPayoutBatch")).isGreaterThan(batchReads);
        assertThat(jdbc.sql("SELECT paypal_item_id FROM payments.payout WHERE reference_id = ?").param(ref).query(String.class)
            .single()).isEqualTo(payout.itemId);
    }

    @Test
    void aSecondCheckInsideTheMinimumGapAsksPayPalNothingAndPayPalOutagesOrRefusalsChangeNothing() throws Exception {
        UUID id = pendingDisbursement();
        SimPayout payout = paypal.payoutOfReference(id.toString());
        assertThat(sync(id).path("status").asText()).isEqualTo("DISBURSING");
        long reads = paypal.callCount("getPayoutItem");
        paypal.settlePayout(payout.itemId, "SUCCESS", null);

        JsonNode inside = sync(id);

        assertThat(inside.path("status").asText()).as("untouched").isEqualTo("DISBURSING");
        assertThat(paypal.callCount("getPayoutItem")).isEqualTo(reads);

        forgetLastRead(id);
        paypal.failNext("getPayoutItem", FakePayPal.unavailable());
        MvcResult outage = syncRequest(id, OFFICER_ONE, "OFFICER");
        assertThat(outage.getResponse().getStatus()).isEqualTo(503);

        forgetLastRead(id);
        paypal.failNext("getPayoutItem", new com.paypalseed.payments.application.ports.PayPalException(
            com.paypalseed.payments.application.ports.PayPalException.Kind.FINAL, 400, "INVALID_REQUEST", "refused"));
        MvcResult refused = syncRequest(id, OFFICER_ONE, "OFFICER");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(loansRegistered(id)).isZero();

        forgetLastRead(id);
        assertThat(sync(id).path("status").asText()).isEqualTo("DISBURSED");
    }

    @Test
    void anApplicationWithNoPayoutOrNoSuchApplicationIs404AndOnlyStaffMayAsk() throws Exception {
        UUID member = newMember();
        UUID approved = approvedApplication(member).id();

        assertThat(syncRequest(approved, OFFICER_ONE, "OFFICER").getResponse().getStatus()).as("never paid out").isEqualTo(404);
        assertThat(syncRequest(UUID.randomUUID(), OFFICER_ONE, "OFFICER").getResponse().getStatus()).as("unknown").isEqualTo(404);

        UUID disbursing = pendingDisbursement();
        assertThat(mvc.perform(asMember(post("/api/v1/desk/applications/{id}/payout/sync", disbursing), member)).andReturn()
            .getResponse().getStatus()).as("a member").isEqualTo(403);
        assertThat(mvc.perform(post("/api/v1/desk/applications/{id}/payout/sync", disbursing)).andReturn().getResponse().getStatus())
            .as("nobody").isEqualTo(401);
        assertThat(syncRequest(disbursing, OFFICER_ONE, "CHECKER").getResponse().getStatus()).isEqualTo(200);
        assertThat(syncRequest(disbursing, OFFICER_ONE, "ADMIN").getResponse().getStatus()).isEqualTo(200);
    }

    // ---- the job ----

    private String sendDirect(long cents) {
        String ref = "reconcile-" + UUID.randomUUID();
        payments.sendPayout(new SendPayout(ref, cents, RecipientType.EMAIL, "member@example.test", PayoutWallet.PAYPAL, "Seed"));
        return ref;
    }

    private JsonNode runJob(String query) throws Exception {
        return read(mvc.perform(post("/internal/jobs/payouts/reconcile" + query).header("Authorization", SERVICE_TOKEN))
            .andExpect(status().isOk()).andReturn());
    }

    @Test
    void theJobReadsEachPayoutThatMayHaveMissedAWebhookAndSkipsOneThatFails() throws Exception {
        String paid = sendDirect(5_000);
        String broken = sendDirect(6_000); // whichever of the two is read first fails once
        String young = sendDirect(7_000);
        String delivered = sendDirect(8_000);
        for (String ref : List.of(paid, broken, young)) {
            paypal.settlePayout(paypal.payoutOfReference(ref).itemId, "SUCCESS", null);
        }
        // The job reads payouts that are not final, asked at least 20 seconds ago; every other payout of this class is out of the way.
        jdbc.sql("UPDATE payments.payout SET created_at = now() + interval '1 day' WHERE status IN ('REQUESTED', 'PENDING', 'UNCLAIMED')")
            .update();
        jdbc.sql("UPDATE payments.payout SET created_at = now() - interval '1 minute' WHERE reference_id IN (?, ?)")
            .param(paid).param(broken).update();
        jdbc.sql("UPDATE payments.payout SET status = 'SUCCEEDED' WHERE reference_id = ?").param(delivered).update();
        paypal.failNext("getPayoutItem", FakePayPal.unavailable());

        JsonNode run = runJob("");

        assertThat(run.path("examined").asInt()).as("not the young payout, not the delivered one").isEqualTo(2);
        assertThat(run.path("failed").asInt()).isEqualTo(1);
        assertThat(run.path("synced").asInt()).isEqualTo(1);
        long succeeded = List.of(paid, broken).stream().filter(r -> payoutStatus(r).equals("SUCCEEDED")).count();
        assertThat(succeeded).as("the one that was read is delivered, the one that failed still waits").isEqualTo(1);
        assertThat(payoutStatus(young)).isEqualTo("PENDING");

        // The next run (the gap lifted) finishes the other one, and the first is announced once.
        jdbc.sql("UPDATE payments.payout SET last_synced_at = NULL").update();
        JsonNode second = runJob("?limit=10");

        assertThat(second.path("failed").asInt()).isZero();
        assertThat(payoutStatus(paid)).isEqualTo("SUCCEEDED");
        assertThat(payoutStatus(broken)).isEqualTo("SUCCEEDED");
        assertThat(events.count(paid)).isEqualTo(1);
        assertThat(events.count(broken)).isEqualTo(1);
        assertThat(sourceOf(paid, "SUCCEEDED")).isEqualTo("POLL");
        assertThat(payments.payoutOf(paid).orElseThrow().status()).isEqualTo(PayoutStatus.SUCCEEDED);
    }

    @Test
    void theJobIsForTheServiceTokenOnlyAndTheLimitIsBounded() throws Exception {
        UUID member = newMember();

        mvc.perform(asMember(post("/internal/jobs/payouts/reconcile"), member)).andExpect(status().is4xxClientError());
        mvc.perform(post("/internal/jobs/payouts/reconcile")).andExpect(status().is4xxClientError());
        mvc.perform(post("/internal/jobs/payouts/reconcile?limit=0").header("Authorization", SERVICE_TOKEN))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/jobs/payouts/reconcile?limit=501").header("Authorization", SERVICE_TOKEN))
            .andExpect(status().isBadRequest());
    }

    // ---- savings withdrawals ----

    @Test
    void refreshingAGoalAlsoEndsItsWithdrawalWhosePayoutPayPalHasDelivered() throws Exception {
        UUID member = newMember();
        String goalId = legacyGoal(member, 25).path("id").asText();
        legacyTopUp(member, goalId, 100);
        JsonNode asked = read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member)
            .header("Idempotency-Key", "w-sync").content("{\"amount\":30}")).andExpect(status().isAccepted()).andReturn());
        String withdrawalId = asked.path("id").asText();
        paypal.settlePayout(paypal.payoutOfReference(withdrawalId).itemId, "SUCCESS", null);
        assertThat(memberSavingsInBooks(member)).as("nothing booked while the payout is in flight").isEqualTo(10_000);

        JsonNode synced = read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/sync", goalId), member))
            .andExpect(status().isOk()).andReturn());

        assertThat(synced.path("reservedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(synced.path("currentBalance").decimalValue()).isEqualByComparingTo("70");
        assertThat(memberSavingsInBooks(member)).isEqualTo(7_000);
        assertThat(jdbc.sql("SELECT status FROM savings.withdrawal WHERE id = ?").param(UUID.fromString(withdrawalId))
            .query(String.class).single()).isEqualTo("SUCCEEDED");
        settle();
        assertThat(memberSavingsInBooks(member)).as("the event the read published finds it done").isEqualTo(7_000);
        assertThat(events.count(withdrawalId)).isEqualTo(1);
        assertLedgerBalanced();
    }
}
