package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.support.FakePayPal.PayoutOutcome;
import com.paypalseed.support.FakePayPal.SimPayout;
import com.paypalseed.savings.api.events.WithdrawalFailedEvent;
import com.paypalseed.savings.api.events.WithdrawalReturnedEvent;
import com.paypalseed.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/**
 * A withdrawal reserves the money, pays it out through PayPal and completes when the payout does. After every
 * step the money in the goal plus the reserve equals what the books hold for the member, and the ledger balances.
 */
@Import({IntegrationTest.Fakes.class, WithdrawalPayoutIT.Recording.class})
class WithdrawalPayoutIT extends MoneyFlowSupport {

    /** Nobody listens for the member-facing withdrawal events yet (engagement will), so the test listens itself. */
    public static class EventLog {
        final List<WithdrawalFailedEvent> failed = new CopyOnWriteArrayList<>();
        final List<WithdrawalReturnedEvent> returned = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(WithdrawalFailedEvent e) {
            failed.add(e);
        }

        @EventListener
        public void on(WithdrawalReturnedEvent e) {
            returned.add(e);
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
    EventLog events;

    private UUID member;
    private String goalId;
    /** Program cash and PayPal payouts are shared by every test of the class, so each test compares with its own start. */
    private long cashBefore;
    private int payoutsBefore;

    private void aGoalWithOneHundredDollars() throws Exception {
        payoutsBefore = paypal.payoutsByBatch.size();
        cashBefore = programCash();
        member = newMember();
        goalId = legacyGoal(member, 25).path("id").asText();
        legacyTopUp(member, goalId, 100);
        assertThat(memberSavingsInBooks(member)).isEqualTo(10_000);
        assertThat(programCash()).isEqualTo(cashBefore + 10_000);
    }

    private int payoutsSent() {
        return paypal.payoutsByBatch.size() - payoutsBefore;
    }

    private JsonNode withdraw(String key, double amount, int expectedStatus) throws Exception {
        return read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member)
                .header("Idempotency-Key", key).content("{\"amount\":%s}".formatted(amount)))
            .andExpect(status().is(expectedStatus)).andReturn());
    }

    private void assertBooksAgreeWithTheGoal() throws Exception {
        JsonNode goal = goal(member, goalId);
        long held = Math.round((goal.path("currentBalance").asDouble() + goal.path("reservedAmount").asDouble()) * 100);
        assertThat(memberSavingsInBooks(member)).as("books = balance + reserve").isEqualTo(held);
        assertLedgerBalanced();
    }

    @Test
    void successReservesFirstThenBooksTheMoneyWhenPayPalConfirms() throws Exception {
        aGoalWithOneHundredDollars();

        JsonNode asked = withdraw("w-success", 30, 202);
        String withdrawalId = asked.path("id").asText();
        assertThat(asked.path("status").asText()).isEqualTo("REQUESTED");
        SimPayout payout = paypal.payoutOfReference(withdrawalId);
        assertThat(payout.amountCents).isEqualTo(3_000);
        assertThat(payout.receiver).as("a PayPal wallet is paid by payer id").isEqualTo(payerIdOf(member));
        assertThat(payout.recipientType).isEqualTo("PAYPAL_ID");
        assertThat(payout.wallet).isEqualTo("PAYPAL");
        JsonNode reserved = goal(member, goalId);
        assertThat(reserved.path("currentBalance").decimalValue()).isEqualByComparingTo("70");
        assertThat(reserved.path("reservedAmount").decimalValue()).isEqualByComparingTo("30");
        assertThat(memberSavingsInBooks(member)).as("nothing booked while the payout is in flight").isEqualTo(10_000);
        assertThat(programCash()).isEqualTo(cashBefore + 10_000);
        assertBooksAgreeWithTheGoal();

        payoutSucceededWebhook(payout);
        settle();

        JsonNode done = goal(member, goalId);
        assertThat(done.path("currentBalance").decimalValue()).isEqualByComparingTo("70");
        assertThat(done.path("reservedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(memberSavingsInBooks(member)).isEqualTo(7_000);
        assertThat(programCash()).isEqualTo(cashBefore + 7_000);
        assertThat(jdbc.sql("SELECT status FROM savings.withdrawal WHERE id = ?").param(UUID.fromString(withdrawalId))
            .query(String.class).single()).isEqualTo("SUCCEEDED");
        assertBooksAgreeWithTheGoal();
    }

    @Test
    void anInstantPayoutCompletesInTheSameRequest() throws Exception {
        aGoalWithOneHundredDollars();
        paypal.payoutOutcome = PayoutOutcome.INSTANT_SUCCESS;

        JsonNode done = withdraw("w-instant", 30, 200);

        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(memberSavingsInBooks(member)).isEqualTo(7_000);
        settle();
        assertThat(memberSavingsInBooks(member)).as("the payout event finds it already done").isEqualTo(7_000);
        assertBooksAgreeWithTheGoal();
    }

    @Test
    void aFailedPayoutGivesTheMoneyBackAndTellsTheMember() throws Exception {
        aGoalWithOneHundredDollars();
        JsonNode asked = withdraw("w-failed", 30, 202);
        SimPayout payout = paypal.payoutOfReference(asked.path("id").asText());

        payoutFailedWebhook(payout, "RECEIVER_UNREGISTERED");
        settle();

        JsonNode goal = goal(member, goalId);
        assertThat(goal.path("currentBalance").decimalValue()).isEqualByComparingTo("100");
        assertThat(goal.path("reservedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(memberSavingsInBooks(member)).isEqualTo(10_000);
        mvc.perform(asMember(get("/api/v1/savings/goals/{id}/withdrawals", goalId), member)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("FAILED")).andExpect(jsonPath("$[0].failureReason").value("RECEIVER_UNREGISTERED"));
        assertThat(events.failed).as("the member-facing event was published once")
            .filteredOn(e -> e.withdrawalId().toString().equals(asked.path("id").asText())).singleElement()
            .satisfies(e -> assertThat(e.reasonCode()).isEqualTo("RECEIVER_UNREGISTERED"));
        assertBooksAgreeWithTheGoal();
    }

    @Test
    void aPayoutFailureReportedInTheCreateResponseIsHandledAtOnce() throws Exception {
        aGoalWithOneHundredDollars();
        paypal.payoutOutcome = PayoutOutcome.FAILED;

        JsonNode done = withdraw("w-refused", 30, 200);

        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(goal(member, goalId).path("currentBalance").decimalValue()).isEqualByComparingTo("100");
        settle();
        assertBooksAgreeWithTheGoal();
    }

    @Test
    void aReturnedPayoutReversesTheBookingAndRestoresTheGoal() throws Exception {
        aGoalWithOneHundredDollars();
        JsonNode asked = withdraw("w-returned", 30, 202);
        SimPayout payout = paypal.payoutOfReference(asked.path("id").asText());
        payoutSucceededWebhook(payout);
        settle();
        assertThat(memberSavingsInBooks(member)).isEqualTo(7_000);

        payoutReturnedWebhook(payout);
        settle();

        JsonNode goal = goal(member, goalId);
        assertThat(goal.path("currentBalance").decimalValue()).isEqualByComparingTo("100");
        assertThat(memberSavingsInBooks(member)).isEqualTo(10_000);
        assertThat(programCash()).isEqualTo(cashBefore + 10_000);
        assertThat(jdbc.sql("SELECT status FROM savings.withdrawal WHERE id = ?").param(UUID.fromString(asked.path("id").asText()))
            .query(String.class).single()).isEqualTo("RETURNED");
        assertThat(events.returned).filteredOn(e -> e.withdrawalId().toString().equals(asked.path("id").asText())).hasSize(1);
        assertBooksAgreeWithTheGoal();
    }

    @Test
    void anUnclaimedPayoutKeepsTheReserveUntilItExpires() throws Exception {
        aGoalWithOneHundredDollars();
        paypal.payoutOutcome = PayoutOutcome.UNCLAIMED;
        JsonNode asked = withdraw("w-unclaimed", 30, 202);
        settle();
        assertThat(goal(member, goalId).path("reservedAmount").decimalValue()).isEqualByComparingTo("30");

        payoutReturnedWebhook(paypal.payoutOfReference(asked.path("id").asText()));
        settle();

        JsonNode goal = goal(member, goalId);
        assertThat(goal.path("reservedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(goal.path("currentBalance").decimalValue()).isEqualByComparingTo("100");
        assertThat(memberSavingsInBooks(member)).as("never delivered, never booked").isEqualTo(10_000);
        assertBooksAgreeWithTheGoal();
    }

    @Test
    void replayingTheSameRequestAndTheSamePayoutEventPaysAndBooksOnce() throws Exception {
        aGoalWithOneHundredDollars();
        JsonNode first = withdraw("w-replay", 30, 202);
        JsonNode replay = withdraw("w-replay", 30, 202);
        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(payoutsSent()).isEqualTo(1);
        assertThat(goal(member, goalId).path("reservedAmount").decimalValue()).isEqualByComparingTo("30");

        SimPayout payout = paypal.payoutOfReference(first.path("id").asText());
        payoutSucceededWebhook(payout);
        payoutSucceededWebhook(payout);
        settle();

        assertThat(memberSavingsInBooks(member)).isEqualTo(7_000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry WHERE source_key = ?")
            .param("savings-withdrawal:" + goalId + ":" + first.path("id").asText()).query(Integer.class).single()).isEqualTo(1);
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member).header("Idempotency-Key", "w-replay")
                .content("{\"amount\":31}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value(Matchers.endsWith("idempotency-key-reused")));
        assertBooksAgreeWithTheGoal();
    }

    @Test
    void theSameMoneyCannotBeWithdrawnTwice() throws Exception {
        aGoalWithOneHundredDollars();
        withdraw("w-one", 70, 202);

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member).header("Idempotency-Key", "w-two")
                .content("{\"amount\":40}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(Matchers.containsString("Insufficient")));

        assertThat(payoutsSent()).isEqualTo(1);
        assertThat(goal(member, goalId).path("reservedAmount").decimalValue()).isEqualByComparingTo("70");
        assertBooksAgreeWithTheGoal();
    }

    @Test
    void aTransientPayPalFailureReservesNothingAndTheSameKeyRetriesCleanly() throws Exception {
        aGoalWithOneHundredDollars();
        paypal.failNext("createPayout", com.paypalseed.support.FakePayPal.unavailable());

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member).header("Idempotency-Key", "w-retry")
            .content("{\"amount\":30}")).andExpect(status().isServiceUnavailable());

        JsonNode afterFailure = goal(member, goalId);
        assertThat(afterFailure.path("currentBalance").decimalValue()).isEqualByComparingTo("100");
        assertThat(afterFailure.path("reservedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM savings.withdrawal WHERE goal_id = ?").param(UUID.fromString(goalId))
            .query(Integer.class).single()).isZero();

        withdraw("w-retry", 30, 202);
        assertThat(goal(member, goalId).path("reservedAmount").decimalValue()).isEqualByComparingTo("30");
        assertBooksAgreeWithTheGoal();
    }

    @Test
    void aVenmoWithdrawalGoesToTheVenmoWallet() throws Exception {
        aGoalWithOneHundredDollars();

        JsonNode asked = read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member)
            .header("Idempotency-Key", "w-venmo").content("{\"amount\":10,\"wallet\":\"VENMO\"}"))
            .andExpect(status().isAccepted()).andReturn());

        assertThat(asked.path("wallet").asText()).isEqualTo("VENMO");
        assertThat(paypal.payoutOfReference(asked.path("id").asText()).wallet).isEqualTo("VENMO");
        assertThat(paypal.payoutOfReference(asked.path("id").asText()).receiver).as("Venmo has no payer id").isEqualTo("ana@example.test");
        assertThat(paypal.payoutOfReference(asked.path("id").asText()).recipientType).isEqualTo("EMAIL");
        settle();
    }

    @Test
    void aMemberWithoutAPayPalEmailCannotWithdrawAndNothingIsReserved() throws Exception {
        payoutsBefore = paypal.payoutsByBatch.size();
        member = UUID.randomUUID(); // never signed up, so identity knows no account to pay into
        goalId = legacyGoal(member, 25).path("id").asText();
        legacyTopUp(member, goalId, 100);

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member).header("Idempotency-Key", "w-nobody")
                .content("{\"amount\":30}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value(Matchers.endsWith("payout-recipient-missing")));

        assertThat(goal(member, goalId).path("reservedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(payoutsSent()).isZero();
    }

    @Test
    void withdrawalNeedsAKeyAndTheOwner() throws Exception {
        aGoalWithOneHundredDollars();
        UUID stranger = newMember();

        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member).content("{\"amount\":30}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.type").value(Matchers.endsWith("idempotency-key-required")));
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), stranger).header("Idempotency-Key", "x")
            .content("{\"amount\":30}")).andExpect(status().isNotFound());
        mvc.perform(asMember(get("/api/v1/savings/goals/{id}/withdrawals", goalId), stranger)).andExpect(status().isNotFound());
        assertThat(goal(member, goalId).path("reservedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_SAVINGS, member))).isEqualTo(10_000);
    }
}
