package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.engagement.application.usecases.ReminderService;
import com.paypalseed.engagement.domain.model.ReminderTemplates.Type;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository.Reminder;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.events.WithdrawalFailedEvent;
import com.paypalseed.support.FakePayPal.SimPayout;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A withdrawal PayPal could not deliver, or took back, reaches the member as a calm message through the same queue as
 * every other message: once per withdrawal, in the app always, by SMS only with a phone and no STOP, and never inside
 * quiet hours.
 */
class WithdrawalMessagesIT extends MoneyFlowSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired
    ReminderRepository reminders;
    @Autowired
    ReminderService reminderService;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    TransactionTemplate tx;

    private UUID member;
    private String goalId;

    private void aGoalWithOneHundredDollars(String phone, String locale, String timezone) throws Exception {
        clock.set(DAY);
        member = newMember();
        goalId = legacyGoal(member, 25).path("id").asText();
        legacyTopUp(member, goalId, 100);
        if (phone != null) {
            reminders.upsertContact(member, phone, locale, timezone);
        }
    }

    private SimPayout withdrawThirty(String key) throws Exception {
        JsonNode asked = read(mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member)
                .header("Idempotency-Key", key).content("{\"amount\":30}")).andExpect(status().isAccepted()).andReturn());
        return paypal.payoutOfReference(asked.path("id").asText());
    }

    private List<Reminder> messages() {
        return reminders.forGoal(UUID.fromString(goalId));
    }

    private Reminder channel(String channel) {
        return messages().stream().filter(m -> m.channel().equals(channel)).findFirst().orElseThrow();
    }

    private String smsStatus() {
        return jdbc.sql("SELECT status FROM engagement.reminder WHERE goal_id = ? AND channel = 'SMS'").param(UUID.fromString(goalId))
                .query(String.class).single();
    }

    @Test
    void aFailedWithdrawalIsToldOnceInTheAppAndBySms() throws Exception {
        aGoalWithOneHundredDollars("+15550007001", "en", "America/Los_Angeles");
        SimPayout payout = withdrawThirty("wm-failed");

        payoutFailedWebhook(payout, "RECEIVER_UNREGISTERED");
        settle();

        assertThat(messages()).hasSize(2);
        Reminder inApp = channel("IN_APP");
        assertThat(inApp.type()).isEqualTo("WITHDRAWAL_FAILED");
        assertThat(inApp.loanId()).as("a goal message names no loan").isNull();
        assertThat(inApp.body()).startsWith("Seed:").contains("$30").contains("back in your goal").contains("no fees")
                .doesNotContain("RECEIVER_UNREGISTERED");
        assertThat(channel("SMS").body()).isEqualTo(inApp.body());
        assertThat(jdbc.sql("SELECT status FROM engagement.reminder WHERE goal_id = ? AND channel = 'IN_APP'").param(UUID.fromString(goalId))
                .query(String.class).single()).isEqualTo("SENT");
        assertThat(smsStatus()).as("noon in Los Angeles is outside quiet hours").isEqualTo("QUEUED");
        assertThat(jdbc.sql("SELECT send_after FROM engagement.reminder WHERE goal_id = ? AND channel = 'SMS'").param(UUID.fromString(goalId))
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(clock.now());

        // PayPal repeats the failure under a new webhook id, and the savings event is published once more: still one message.
        payoutFailedWebhook(payout, "RECEIVER_UNREGISTERED");
        tx.executeWithoutResult(s -> publisher.publishEvent(new WithdrawalFailedEvent(Ids.newId(), member.toString(), UUID.fromString(goalId),
                UUID.fromString(jdbc.sql("SELECT id FROM savings.withdrawal WHERE goal_id = ?").param(UUID.fromString(goalId))
                        .query(String.class).single()), Money.ofCents(3_000), "RECEIVER_UNREGISTERED", Instant.now())));
        settle();
        assertThat(messages()).hasSize(2);
    }

    @Test
    void aReturnedWithdrawalIsTold() throws Exception {
        aGoalWithOneHundredDollars("+15550007002", "en", "America/Los_Angeles");
        SimPayout payout = withdrawThirty("wm-returned");
        payoutSucceededWebhook(payout);
        settle();
        assertThat(messages()).as("a withdrawal that worked needs no message").isEmpty();

        payoutReturnedWebhook(payout);
        settle();

        assertThat(messages()).hasSize(2);
        assertThat(channel("IN_APP").type()).isEqualTo("WITHDRAWAL_RETURNED");
        assertThat(channel("IN_APP").body()).contains("$30").contains("sent your").contains("in your goal again").contains("no fees");
        assertLedgerBalanced();
    }

    @Test
    void aMemberWithoutAPhoneGetsTheAppMessageOnly() throws Exception {
        aGoalWithOneHundredDollars(null, null, null);
        payoutFailedWebhook(withdrawThirty("wm-nophone"), "RECEIVER_UNREGISTERED");
        settle();

        assertThat(smsStatus()).isEqualTo("SKIPPED_NO_CONTACT");
        assertThat(channel("IN_APP").body()).contains("$30");
    }

    @Test
    void aMemberWhoSaidStopGetsNoText() throws Exception {
        aGoalWithOneHundredDollars("+15550007003", "en", "America/Los_Angeles");
        reminderService.setOptOut(member, true);

        payoutFailedWebhook(withdrawThirty("wm-stop"), "RECEIVER_UNREGISTERED");
        settle();

        assertThat(smsStatus()).isEqualTo("SKIPPED_OPT_OUT");
        assertThat(channel("IN_APP").body()).as("the app copy is not a text and still arrives").contains("$30");
    }

    @Test
    void theMessageFollowsTheMembersLanguage() throws Exception {
        aGoalWithOneHundredDollars("+15550007004", "es", "America/Los_Angeles");
        payoutFailedWebhook(withdrawThirty("wm-es"), "RECEIVER_UNREGISTERED");
        settle();

        assertThat(channel("IN_APP").body()).startsWith("Seed:").contains("$30").contains("no hay cargos").contains("tu meta");
    }

    @Test
    void aTextIsHeldUntilQuietHoursEnd() throws Exception {
        // Noon in Los Angeles is 05:00 the next morning in Tokyo.
        aGoalWithOneHundredDollars("+15550007005", "en", "Asia/Tokyo");
        payoutFailedWebhook(withdrawThirty("wm-quiet"), "RECEIVER_UNREGISTERED");
        settle();

        Instant sendAfter = jdbc.sql("SELECT send_after FROM engagement.reminder WHERE goal_id = ? AND channel = 'SMS'")
                .param(UUID.fromString(goalId)).query(java.time.OffsetDateTime.class).single().toInstant();
        assertThat(sendAfter).isAfter(clock.now());
        assertThat(sendAfter.atZone(java.time.ZoneId.of("Asia/Tokyo")).toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
        assertThat(smsStatus()).isEqualTo("QUEUED");
        assertThat(channel("IN_APP").body()).as("the app copy is there at once").isNotBlank();

        reminderService.dispatchDue(100);
        assertThat(smsStatus()).as("nothing is sent while the member sleeps").isEqualTo("QUEUED");
        clock.set(DAY.plusDays(1));
        reminderService.dispatchDue(100);
        assertThat(smsStatus()).isEqualTo("SENT");
    }

    @Test
    void aGoalMessageDoesNotHideTheMembersLastLoanFromAnSmsReply() throws Exception {
        aGoalWithOneHundredDollars("+15550007006", "en", "America/Los_Angeles");
        LoanSummary loan = disburse(member, 300, 3, 15, DAY);
        reminderService.remind(member, loan.loanId(), Type.DUE_SOON, "v1:s1", 10_000, DAY.plusDays(3));

        payoutFailedWebhook(withdrawThirty("wm-loan"), "RECEIVER_UNREGISTERED");
        settle();

        assertThat(messages()).hasSize(2);
        assertThat(reminders.latestLoanOf(member)).as("HELP by text still finds the loan").contains(loan.loanId());
        assertThat(reminders.forLoan(loan.loanId())).as("the loan keeps its own two messages").hasSize(2);
    }
}
