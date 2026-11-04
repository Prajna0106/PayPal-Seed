package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.engagement.application.usecases.ReminderService;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository.Reminder;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.FakePayPal.PayoutOutcome;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * What the rest of the system does when a loan never stood (ADR 0024 follow-up): a payout PayPal took back, or a loan the
 * member cancelled in the cooling-off window. The desk shows the loan as void and out of its figures and the application
 * as needing another payout; the member's queued messages are cancelled and one calm message is sent; an open hardship
 * case ends with a clear reason; and a cooling-off cancellation closes its loan row.
 */
@TestPropertySource(properties = "seed.hardship.agent-deadline=PT0S")
class LoanVoidFollowUpIT extends MoneyFlowSupport {

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
    ReminderRepository contacts;
    @Autowired
    ReminderService reminderService;

    // ---- desk-query ----

    @Test
    void theDeskShowsTheReturnedLoanAsVoidAndItsApplicationAsNeedingAnotherPayout() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        JsonNode before = deskRow("/api/v1/desk/portfolio/loans", "loanId", loanId);
        assertThat(before.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(before.path("outstandingCents").asLong()).isEqualTo(120_000);
        long exposureBefore = summary().path("outstandingCents").asLong();
        long loansIssuedBefore = summary().path("impact").path("loansIssued").asLong();

        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        JsonNode loan = deskRow("/api/v1/desk/portfolio/loans", "loanId", loanId);
        assertThat(loan.path("status").asText()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(loan.path("outstandingCents").asLong()).isZero();
        assertThat(loan.path("nextDueDate").isMissingNode() || loan.path("nextDueDate").isNull()).isTrue();
        assertThat(loan.path("agingBucket").asText()).isEqualTo("CURRENT");
        assertThat(rows(staff("/api/v1/desk/portfolio/loans", "status", "DISBURSEMENT_RETURNED")))
                .extracting(r -> r.path("loanId").asText()).contains(loanId.toString());
        assertThat(rows(staff("/api/v1/desk/portfolio/loans", "status", "ACTIVE")))
                .extracting(r -> r.path("loanId").asText()).doesNotContain(loanId.toString());

        JsonNode summary = summary();
        assertThat(summary.path("outstandingCents").asLong()).as("the void loan leaves the exposure")
                .isEqualTo(exposureBefore - 120_000);
        assertThat(summary.path("impact").path("loansIssued").asLong()).as("and the inclusion figures")
                .isEqualTo(loansIssuedBefore - 1);
        assertThat(summary.path("byStatus")).anySatisfy(s -> {
            assertThat(s.path("status").asText()).isEqualTo("DISBURSEMENT_RETURNED");
            assertThat(s.path("outstandingCents").asLong()).isZero();
        });

        JsonNode application = deskRow("/api/v1/desk/applications", "applicationId", id);
        assertThat(application.path("status").asText()).isEqualTo("APPROVED");
        assertThat(application.path("payoutIssue").asText()).isEqualTo("RETURNED_AFTER_DISBURSEMENT");
        assertThat(rows(staff("/api/v1/desk/applications", "status", "APPROVED")))
                .anySatisfy(r -> assertThat(r.path("applicationId").asText()).isEqualTo(id.toString()));

        // The issue going away when the application is paid again is proved in the next test; rejecting it, in
        // VoidedLoanProjectionTest.
    }

    @Test
    void whenAnOfficerPaysAgainTheDeskShowsTheNewLoanAndTheApplicationBackAtDisbursedWithNoIssue() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID voided = registeredLoan(id);
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();
        assertThat(deskRow("/api/v1/desk/applications", "applicationId", id).path("payoutIssue").asText())
                .isEqualTo("RETURNED_AFTER_DISBURSEMENT");
        long exposureWhileVoid = summary().path("outstandingCents").asLong();

        paypal.payoutOutcome = PayoutOutcome.INSTANT_SUCCESS;
        mvc.perform(staffAs(post("/api/v1/desk/loans/applications/{id}/disburse", id), OFFICER_TWO, "OFFICER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISBURSED"));
        settle();

        assertThat(paypal.payoutOfReference(id + "-a2").amountCents).as("a new payout under a new reference").isEqualTo(120_000);
        UUID live = jdbc.sql("SELECT id FROM lending.loan WHERE disbursement_ref = ?").param("loan-" + id + "-disb-v1")
                .query(UUID.class).single();
        assertThat(live).as("the loan is registered again under a new reference").isNotEqualTo(voided);

        JsonNode application = deskRow("/api/v1/desk/applications", "applicationId", id);
        assertThat(application.path("status").asText()).isEqualTo("DISBURSED");
        assertThat(application.path("payoutIssue").isMissingNode() || application.path("payoutIssue").isNull())
                .as("the issue is gone once the application is paid again").isTrue();
        assertThat(rows(staff("/api/v1/desk/applications", "status", "APPROVED")))
                .extracting(r -> r.path("applicationId").asText()).doesNotContain(id.toString());
        JsonNode newLoan = deskRow("/api/v1/desk/portfolio/loans", "loanId", live);
        assertThat(newLoan.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(newLoan.path("outstandingCents").asLong()).isEqualTo(120_000);
        assertThat(deskRow("/api/v1/desk/portfolio/loans", "loanId", voided).path("status").asText())
                .isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(summary().path("outstandingCents").asLong()).isEqualTo(exposureWhileVoid + 120_000);
        assertThat(queries.loan(live).status().name()).isEqualTo("ACTIVE");
    }

    // ---- engagement ----

    @Test
    void theMemberGetsOneCalmMessageAndTheQueuedOnesForThatLoanAreCancelled() throws Exception {
        UUID member = newMember();
        contacts.upsertContact(member, "+15550007001", "en", "America/Los_Angeles");
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        contacts.enqueue("loan:" + loanId + ":v1:s1:DUE_SOON:SMS", member, loanId, "DUE_SOON", "SMS", "en", "Seed: Your payment is due",
                java.time.Instant.parse("2030-01-01T00:00:00Z"), "QUEUED");

        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        assertThat(statusOf("loan:" + loanId + ":v1:s1:DUE_SOON:SMS")).as("the reminder for the void loan never goes out").isEqualTo("CANCELLED");
        List<Reminder> told = messagesOfType(loanId, "LOAN_VOIDED");
        assertThat(told).extracting(Reminder::channel).containsExactlyInAnyOrder("IN_APP", "SMS");
        assertThat(told).allSatisfy(m -> assertThat(m.body()).startsWith("Seed:").contains("didn't reach your PayPal account")
                .contains("You owe nothing").contains("try again once your PayPal account is fixed")
                .doesNotContain("RETURNED").doesNotContain("$"));
        assertThat(statusOf("loan:" + loanId + ":voided:SMS")).as("the one message to send is not cancelled by the cancel")
                .isEqualTo("QUEUED");

        // delivered twice, or asked for again: still one message, nothing else cancelled
        reminderService.loanVoided(member, loanId, 0, false);
        applications.onPayoutReturned(id.toString(), "RETURNED");
        settle();
        assertThat(messagesOfType(loanId, "LOAN_VOIDED")).hasSize(2);

        // and a message decided for this loan before the void but delivered after it is dropped
        reminderService.remind(member, loanId, com.paypalseed.engagement.domain.model.ReminderTemplates.Type.DUE_SOON, "v1:s2", 15_000,
                LocalDate.of(2027, 1, 15));
        assertThat(contacts.forLoan(loanId).stream().filter(r -> r.dedupeKey().contains("v1:s2")).toList()).isEmpty();
    }

    @Test
    void aMemberWhoAlreadyPaidIsToldTheirMoneyIsComingBack() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        webhook(com.paypalseed.support.PayPalWebhooks.captureCompleted(com.paypalseed.support.PayPalWebhooks.newEventId(), "CAP-VOID-FU-"
                + loanId, loanId, 15_000));
        settle();

        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        Reminder inApp = messagesOfType(loanId, "LOAN_VOIDED").stream().filter(m -> m.channel().equals("IN_APP")).findFirst().orElseThrow();
        assertThat(inApp.body()).contains("$150 you paid is coming back");
    }

    // ---- hardship ----

    @Test
    void anOpenHardshipCaseOnTheReturnedLoanEndsAndNothingFollows() throws Exception {
        UUID member = newMember();
        contacts.upsertContact(member, "+15550007002", "en", "America/Los_Angeles");
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        UUID caseId = openCase(member, loanId);
        assertThat(caseStatus(caseId)).isEqualTo("OUTREACH_SENT");
        long hardshipMessages = messagesOfTypePrefix(loanId, "HARDSHIP").size();
        assertThat(hardshipMessages).as("the offer went out before the payout came back").isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_hardship_flag WHERE loan_id = ?").param(loanId).query(Integer.class).single())
                .isEqualTo(1);

        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        assertThat(caseStatus(caseId)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.sql("SELECT closed_reason FROM hardship.hardship_case WHERE id = ?").param(caseId).query(String.class).single())
                .isEqualTo("LOAN_VOIDED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_hardship_flag WHERE loan_id = ?").param(loanId).query(Integer.class).single())
                .as("the open-case flag on the loan is cleared").isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM hardship.case_timer WHERE case_id = ? AND fired_at IS NULL")
                .param(caseId).query(Integer.class).single()).isZero();
        assertThat(rows(staff("/api/v1/desk/portfolio/loans", "hardship", "true")))
                .extracting(r -> r.path("loanId").asText()).doesNotContain(loanId.toString());

        // sweeps, wake-ups and the daily scan open nothing and send nothing for this loan
        mvc.perform(post("/internal/jobs/hardship/sweep").header("Authorization", SERVICE_TOKEN)).andExpect(status().isOk());
        mvc.perform(post("/internal/jobs/hardship/scan").header("Authorization", SERVICE_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"" + clock.today() + "\",\"shardGroup\":0,\"shardCount\":1,\"limit\":500}")).andExpect(status().isOk());
        settle();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM hardship.hardship_case WHERE loan_id = ?").param(loanId).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(messagesOfTypePrefix(loanId, "HARDSHIP")).hasSize((int) hardshipMessages);
        assertThat(messagesOfTypePrefix(loanId, "HARDSHIP").stream().filter(m -> m.channel().equals("SMS")).map(Reminder::dedupeKey)
                .map(this::statusOf)).as("an offer SMS still waiting is cancelled, never sent").doesNotContain("QUEUED");
    }

    // ---- lending: the cooling-off cancel ----

    @Test
    void cancellingInTheCoolingOffWindowClosesTheLoanRowAndTellsEveryoneItNeverStood() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        assertThat(queries.loan(loanId).status().name()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_timer WHERE loan_id = ? AND fired_on IS NULL").param(loanId)
                .query(Long.class).single()).isPositive();

        mvc.perform(post("/api/v1/loans/applications/{id}/cooling-off-cancel", id).header("X-Seed-Member-Id", member.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED_COOLING_OFF"));
        settle();

        var loan = queries.loan(loanId);
        assertThat(loan.status().name()).isEqualTo("CANCELLED");
        assertThat(loan.outstandingCents()).isZero();
        assertThat(loan.closedOn()).isNotNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_timer WHERE loan_id = ? AND fired_on IS NULL").param(loanId)
                .query(Long.class).single()).as("no timer is left to fire").isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.collection_attempt WHERE loan_id = ? AND status = 'PLANNED'").param(loanId)
                .query(Long.class).single()).isZero();
        assertThat(queries.collectionSpec(loanId).collectible()).isFalse();
        assertThat(receivable(loanId)).isZero();
        assertIntegrity(loanId);
        assertLedgerBalanced();

        // a later close of business neither misses an instalment nor moves a bucket
        clock.set(LocalDate.of(2027, 3, 1));
        cob.catchUp(LocalDate.of(2027, 3, 1), "test");
        settle();
        assertThat(queries.loan(loanId).status().name()).isEqualTo("CANCELLED");
        assertThat(queries.loan(loanId).outstandingCents()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.instalment_missed WHERE loan_id = ?").param(loanId).query(Long.class).single()).isZero();

        // the desk, the member and the record agree
        assertThat(deskRow("/api/v1/desk/portfolio/loans", "loanId", loanId).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(deskRow("/api/v1/desk/applications", "applicationId", id).path("status").asText()).isEqualTo("CANCELLED_COOLING_OFF");
        assertThat(deskRow("/api/v1/desk/applications", "applicationId", id).path("payoutIssue").isMissingNode()
                || deskRow("/api/v1/desk/applications", "applicationId", id).path("payoutIssue").isNull()).isTrue();
        assertThat(lending.loansOfMember(member)).singleElement().satisfies(l -> {
            assertThat(l.status()).isEqualTo("CANCELLED");
            assertThat(l.outstandingCents()).isZero();
            assertThat(l.nextDue()).isNull();
        });
        Reminder confirmation = messagesOfType(loanId, "LOAN_VOIDED").stream().filter(m -> m.channel().equals("IN_APP")).findFirst().orElseThrow();
        assertThat(confirmation.body()).isEqualTo("Seed: Your loan is cancelled. You owe nothing and there are no fees.");

        // cancelling again is refused, and nothing doubles
        mvc.perform(post("/api/v1/loans/applications/{id}/cooling-off-cancel", id).header("X-Seed-Member-Id", member.toString()))
                .andExpect(status().isConflict());
        settle();
        assertThat(messagesOfType(loanId, "LOAN_VOIDED")).hasSize(2);
    }

    // ---- helpers ----

    @Autowired
    com.paypalseed.lending.application.usecases.CloseOfBusiness cob;

    private UUID approvedApplication(UUID member) {
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
        return applications.approveApplication(submitted.id(), OFFICER_TWO).id();
    }

    private UUID registeredLoan(UUID applicationId) throws Exception {
        mvc.perform(staffAs(post("/api/v1/desk/loans/applications/{id}/disburse", applicationId), OFFICER_TWO, "OFFICER"))
                .andExpect(status().isOk());
        payoutSucceededWebhook(paypal.payoutOfReference(applicationId.toString()));
        settle();
        return jdbc.sql("SELECT id FROM lending.loan WHERE disbursement_ref = ?").param("loan-" + applicationId + "-disb")
                .query(UUID.class).single();
    }

    /** The scan opens a case for a member whose income fell, the sweep sends the offer: as in HardshipLifecycleIT. */
    private UUID openCase(UUID member, UUID loanId) throws Exception {
        LocalDate due = lending.currentSchedule(loanId).instalments().get(0).dueDate();
        LocalDate scanDate = due.minusDays(8);
        clock.set(scanDate);
        List<String> weeks = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            weeks.add("{\"weekStart\":\"" + scanDate.minusDays(91 - 7L * i) + "\",\"inflowCents\":" + (i < 9 ? 20_000 : 9_600) + "}");
        }
        mvc.perform(put("/internal/underwriting/members/{id}/income", member).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", SERVICE_TOKEN).content("{\"consent\":true,\"weeks\":[" + String.join(",", weeks) + "]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/jobs/hardship/scan").header("Authorization", SERVICE_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + scanDate + "\",\"shardGroup\":0,\"shardCount\":1,\"limit\":500}"))
                .andExpect(status().isOk());
        UUID caseId = jdbc.sql("SELECT id FROM hardship.hardship_case WHERE loan_id = ?").param(loanId).query(UUID.class).single();
        settle();
        mvc.perform(post("/internal/jobs/hardship/sweep").header("Authorization", SERVICE_TOKEN)).andExpect(status().isOk());
        settle();
        return caseId;
    }

    private String caseStatus(UUID caseId) {
        return jdbc.sql("SELECT status FROM hardship.hardship_case WHERE id = ?").param(caseId).query(String.class).single();
    }

    private String statusOf(String dedupeKey) {
        return jdbc.sql("SELECT status FROM engagement.reminder WHERE dedupe_key = ?").param(dedupeKey).query(String.class).single();
    }

    private List<Reminder> messagesOfType(UUID loanId, String type) {
        return contacts.forLoan(loanId).stream().filter(r -> r.type().equals(type)).toList();
    }

    private List<Reminder> messagesOfTypePrefix(UUID loanId, String prefix) {
        return contacts.forLoan(loanId).stream().filter(r -> r.type().startsWith(prefix)).toList();
    }

    private static MockHttpServletRequestBuilder staffAs(MockHttpServletRequestBuilder request, UUID staffId, String roles) {
        return request.header("X-Seed-Staff-Id", staffId.toString()).header("X-Seed-Staff-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode staff(String path, String... params) throws Exception {
        MockHttpServletRequestBuilder request = staffAs(get(path), UUID.randomUUID(), "OFFICER").param("limit", "200");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return read(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private JsonNode summary() throws Exception {
        return staff("/api/v1/desk/portfolio/summary");
    }

    private JsonNode deskRow(String path, String field, UUID value) throws Exception {
        return rows(staff(path)).stream().filter(r -> r.path(field).asText().equals(value.toString())).findFirst()
                .orElseThrow(() -> new AssertionError("no row with " + field + "=" + value + " in " + path));
    }

    private static List<JsonNode> rows(JsonNode page) {
        List<JsonNode> rows = new ArrayList<>();
        page.path("rows").forEach(rows::add);
        return rows;
    }
}
