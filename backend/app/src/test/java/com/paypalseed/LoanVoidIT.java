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
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.CloseOfBusiness;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.FakePayPal.PayoutOutcome;
import com.paypalseed.support.FakePayPal.SimPayout;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A payout PayPal takes back after the loan was registered voids the loan (ADR 0024): the disbursement is reversed in
 * the ledger by a new entry, the loan closes with nothing owed and nothing planned, the application can be paid again
 * under a new reference or rejected, and a replay, an earlier attempt's report or a withdrawal payout change nothing.
 */
@Import({IntegrationTest.Fakes.class, LoanVoidIT.Recording.class})
class LoanVoidIT extends MoneyFlowSupport {

    public static class VoidLog {
        final List<LendingEvents.LoanVoided> voided = new CopyOnWriteArrayList<>();
        final List<LendingEvents.VoidedLoanPaymentHeld> held = new CopyOnWriteArrayList<>();

        public List<LendingEvents.LoanVoided> voided() {
            return voided;
        }

        public List<LendingEvents.VoidedLoanPaymentHeld> held() {
            return held;
        }

        @TransactionalEventListener
        public void on(LendingEvents.LoanVoided e) {
            voided.add(e);
        }

        @TransactionalEventListener
        public void on(LendingEvents.VoidedLoanPaymentHeld e) {
            held.add(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {
        @Bean
        VoidLog voidLog() {
            return new VoidLog();
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
    CloseOfBusiness cob;
    @Autowired
    ReserveFacade reserve;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    VoidLog voidLog;

    private static final UUID OFFICER_ONE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final UUID OFFICER_TWO = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");

    // ---- the unwind ----

    @Test
    void aReturnedPayoutVoidsTheRegisteredLoanAndPutsTheApplicationBackInTheOfficersHands() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        long cashBeforeLoan = programCash();
        UUID loanId = registeredLoan(id);
        assertThat(programCash()).isEqualTo(cashBeforeLoan - 120_000);
        assertThat(receivable(loanId)).isEqualTo(120_000);
        assertThat(count("SELECT COUNT(*) FROM lending.loan_timer WHERE loan_id = ?", loanId)).as("timers exist while live").isPositive();
        long entriesBefore = journalEntries();

        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        // the loan: closed, owes nothing, plans nothing
        var loan = queries.loan(loanId);
        assertThat(loan.status().name()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(loan.outstandingCents()).isZero();
        assertThat(loan.overpaidCents()).isZero();
        assertThat(loan.closedOn()).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM lending.loan_timer WHERE loan_id = ? AND fired_on IS NULL", loanId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM lending.collection_attempt WHERE loan_id = ? AND status = 'PLANNED'", loanId)).isZero();

        // the books: one new entry reversed the disbursement; nothing was edited
        assertThat(journalEntries() - entriesBefore).isEqualTo(1);
        assertThat(receivable(loanId)).isZero();
        assertThat(programCash()).as("the money is back in the program account").isEqualTo(cashBeforeLoan);
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, member))).isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger.journal_entry WHERE source_key = ?", "loan-void:" + loanId)).isEqualTo(1);
        assertIntegrity(loanId);
        assertLedgerBalanced();

        // the application: approved again, loan detached, the reason where the desk reads it
        JsonNode app = application(member, id);
        assertThat(app.path("status").asText()).isEqualTo("APPROVED");
        assertThat(app.path("payoutIssue").asText()).isEqualTo("RETURNED_AFTER_DISBURSEMENT");
        assertThat(app.path("loanId").isNull() || app.path("loanId").isMissingNode()).isTrue();
        assertThat(app.path("coolingOffExpiresAt").isNull() || app.path("coolingOffExpiresAt").isMissingNode()).isTrue();

        // the audit chain and the event
        assertThat(count("SELECT COUNT(*) FROM governance.audit_entry WHERE entry_type = 'LOAN_DISBURSEMENT_RETURNED' AND subject_id = ?",
                loanId)).isEqualTo(1);
        assertThat(voidLog.voided()).filteredOn(e -> e.loanId().equals(loanId)).singleElement().satisfies(e -> {
            assertThat(e.applicationId()).isEqualTo(id);
            assertThat(e.payoutReference()).isEqualTo(id.toString());
            assertThat(e.principalCents()).isEqualTo(120_000);
            assertThat(e.refundDueCents()).isZero();
            assertThat(e.reserveCoverCents()).isZero();
        });

        // the member sees no loan, owes nothing, and may apply again later
        List<MemberLoanView> shown = lending.loansOfMember(member);
        assertThat(shown).isEmpty();
        assertThat(lending.eligibilityOf(member).hasActiveLoan()).isFalse();
    }

    @Test
    void nothingRunsOnAVoidedLoanNotEvenWhenItsFirstDueDateHasLongPassed() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();
        long entries = journalEntries();

        clock.set(LocalDate.of(2027, 3, 1));
        cob.catchUp(LocalDate.of(2027, 3, 1), "test");
        settle();

        assertThat(count("SELECT COUNT(*) FROM lending.instalment_missed WHERE loan_id = ?", loanId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM lending.bucket_transition WHERE loan_id = ?", loanId)).isZero();
        assertThat(queries.loan(loanId).status().name()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(queries.loan(loanId).dpd()).isZero();
        assertThat(journalEntries()).isEqualTo(entries);
        assertThat(queries.collectionSpec(loanId).collectible()).isFalse();
    }

    @Test
    void theSameReturnAgainChangesNothing() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        SimPayout payout = paypal.payoutOfReference(id.toString());
        payoutReturnedWebhook(payout);
        settle();
        long entries = journalEntries();
        int voids = voidLog.voided().size();

        payoutReturnedWebhook(payout); // PayPal sends it again under a new event id
        applications.onPayoutReturned(id.toString(), "RETURNED"); // and the listener is delivered twice
        settle();

        assertThat(journalEntries()).isEqualTo(entries);
        assertThat(voidLog.voided()).hasSize(voids);
        assertThat(count("SELECT COUNT(*) FROM governance.audit_entry WHERE entry_type = 'LOAN_DISBURSEMENT_RETURNED' AND subject_id = ?",
                loanId)).isEqualTo(1);
        assertThat(application(member, id).path("status").asText()).isEqualTo("APPROVED");
        assertLedgerBalanced();
    }

    @Test
    void afterTheRecipientIsFixedTheApplicationIsPaidAgainUnderNewReferencesAndTheNewLoanIsLive() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID voided = registeredLoan(id);
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        paypal.payoutOutcome = PayoutOutcome.INSTANT_SUCCESS;
        JsonNode again = disburse(id, 200);
        settle();

        assertThat(again.path("status").asText()).isEqualTo("DISBURSED");
        assertThat(again.path("disbursementRef").asText()).isEqualTo("loan-" + id + "-disb-v1");
        UUID live = UUID.fromString(again.path("loanId").asText());
        assertThat(live).isNotEqualTo(voided);
        assertThat(paypal.payoutOfReference(id + "-a2").amountCents).isEqualTo(120_000);
        assertThat(queries.loan(voided).status().name()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(queries.loan(live).status().name()).isEqualTo("ACTIVE");
        assertThat(receivable(live)).isEqualTo(120_000);
        assertThat(receivable(voided)).isZero();
        assertThat(lending.loansOfMember(member)).singleElement().satisfies(l -> assertThat(l.loanId()).isEqualTo(live));
        assertIntegrity(live);
        assertIntegrity(voided);
        assertLedgerBalanced();

        // the first attempt's late report cannot touch the new loan
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();
        assertThat(queries.loan(live).status().name()).isEqualTo("ACTIVE");
        assertThat(application(member, id).path("status").asText()).isEqualTo("DISBURSED");

        // and the second payout coming back voids the second loan, with its own reference
        payoutReturnedWebhook(paypal.payoutOfReference(id + "-a2"));
        settle();
        assertThat(queries.loan(live).status().name()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(application(member, id).path("status").asText()).isEqualTo("APPROVED");
        paypal.payoutOutcome = PayoutOutcome.INSTANT_SUCCESS;
        assertThat(disburse(id, 200).path("disbursementRef").asText()).isEqualTo("loan-" + id + "-disb-v2");
        settle();
        assertLedgerBalanced();
    }

    @Test
    void anOfficerCanRejectAnApplicationWhosePayoutCameBackInsteadOfPayingAgain() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/reject", id), OFFICER_ONE, "OFFICER")
                .content("{\"reason\":\"Recipient could not be fixed\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(application(member, id).path("status").asText()).isEqualTo("REJECTED");
        assertThat(queries.loan(loanId).status().name()).isEqualTo("DISBURSEMENT_RETURNED");
        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", id), OFFICER_TWO, "OFFICER"))
            .andExpect(status().isConflict());
        settle();
    }

    @Test
    void anApprovedApplicationWithNoPayoutTroubleStillCannotBeRejected() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);

        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/reject", id), OFFICER_ONE, "OFFICER").content("{}"))
            .andExpect(status().isConflict());

        assertThat(application(member, id).path("status").asText()).isEqualTo("APPROVED");
        settle();
    }

    // ---- when the order of events is not the order of things ----

    @Test
    void aReturnThatArrivesBeforeTheSuccessLeavesNoLoanAndALateSuccessChangesNothing() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        disburse(id, 200);
        SimPayout payout = paypal.payoutOfReference(id.toString());
        long entries = journalEntries();

        payoutReturnedWebhook(payout);
        settle();
        payoutSucceededWebhook(payout); // the success report was delayed behind the return
        settle();

        assertThat(application(member, id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(count("SELECT COUNT(*) FROM lending.loan WHERE disbursement_ref LIKE ?", "loan-" + id + "%")).isZero();
        assertThat(journalEntries()).isEqualTo(entries);
        assertThat(voidLog.voided()).filteredOn(e -> e.applicationId().equals(id)).isEmpty();
    }

    @Test
    void aReturnReportedForAnythingButAnApplicationsCurrentPayoutChangesNothing() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        long entries = journalEntries();

        applications.onPayoutReturned(UUID.randomUUID().toString(), "RETURNED");       // a savings withdrawal's reference
        applications.onPayoutReturned("withdrawal-" + UUID.randomUUID(), "RETURNED");   // not even a UUID
        applications.onPayoutReturned(id + "-a7", "RETURNED");                          // an attempt that never happened
        applications.onPayoutReturned(null, "RETURNED");
        settle();

        assertThat(queries.loan(loanId).status().name()).isEqualTo("ACTIVE");
        assertThat(application(member, id).path("status").asText()).isEqualTo("DISBURSED");
        assertThat(journalEntries()).isEqualTo(entries);
        assertThat(receivable(loanId)).isEqualTo(120_000);
    }

    // ---- money the member had already paid ----

    @Test
    void whatTheMemberPaidBeforeTheReturnIsHeldForThemAndRefundedAndNeverCounted() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-VOID-1", loanId, 30_000));
        settle();
        assertThat(receivable(loanId)).isEqualTo(90_000);
        long factsBefore = recordFacts(member);

        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        var loan = queries.loan(loanId);
        assertThat(loan.status().name()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(loan.outstandingCents()).isZero();
        assertThat(voidLog.voided()).filteredOn(e -> e.loanId().equals(loanId)).singleElement()
            .satisfies(e -> assertThat(e.refundDueCents()).isEqualTo(30_000));
        assertThat(paypal.callCount("refundCapture")).as("the 30,000 went back through PayPal").isEqualTo(1);
        assertThat(loan.overpaidCents()).as("the refund was recorded and nothing is held any more").isZero();
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, member))).isZero();
        assertThat(receivable(loanId)).isZero();
        assertThat(queries.transactions(loan)).extracting(t -> t.type()).containsExactly("RECEIPT", "OVERPAYMENT_REFUND");
        assertIntegrity(loanId);
        assertLedgerBalanced();
        assertThat(recordFacts(member)).as("the record learns nothing more from a loan that never was").isEqualTo(factsBefore);

        // a replay does not refund twice
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);
    }

    @Test
    void aPaymentThatReachesAVoidedLoanIsHeldAndRefundedNotAppliedToAScheduleThatIsGone() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-LATE-VOID", loanId, 15_000));
        settle();

        var loan = queries.loan(loanId);
        assertThat(loan.status().name()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(loan.outstandingCents()).isZero();
        assertThat(loan.overpaidCents()).isZero();
        assertThat(voidLog.held()).filteredOn(e -> e.loanId().equals(loanId)).singleElement()
            .satisfies(e -> assertThat(e.amountCents()).isEqualTo(15_000));
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, member))).isZero();
        assertIntegrity(loanId);
        assertLedgerBalanced();
    }

    // ---- money the fund had paid ----

    @Test
    void whatTheFundPaidOnAVoidedLoanGoesBackToTheFundInTheSameEntry() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        tx.execute(s -> reserve.recordDonation("CAP-void-fund-" + UUID.randomUUID(), 50_000, 0, null, true));
        long fundBefore = reserve.summary().balanceCents();
        long reserveLedgerBefore = ledger.balanceCents(AccountRef.program(AccountType.LOSS_RESERVE));
        UUID cover = coverApprovedByTwoOfficers(member, loanId, 20_000, "void-cover");
        settle();
        assertThat(reserve.summary().balanceCents()).isEqualTo(fundBefore - 20_000);
        assertThat(receivable(loanId)).isEqualTo(100_000);

        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        assertThat(queries.loan(loanId).status().name()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(voidLog.voided()).filteredOn(e -> e.loanId().equals(loanId)).singleElement().satisfies(e -> {
            assertThat(e.reserveCoverCents()).isEqualTo(20_000);
            assertThat(e.refundDueCents()).as("the member paid nothing; the fund's money is not theirs").isZero();
        });
        assertThat(receivable(loanId)).isZero();
        assertThat(ledger.balanceCents(AccountRef.program(AccountType.LOSS_RESERVE))).isEqualTo(reserveLedgerBefore);
        assertThat(reserve.summary().balanceCents()).as("the public figure agrees with the books").isEqualTo(fundBefore);
        assertThat(count("SELECT returned_cents FROM reserve.cover_request WHERE id = ?", cover)).isEqualTo(20_000);
        assertThat(count("SELECT COUNT(*) FROM reserve.cover_return WHERE return_ref = ?", "loan-void:" + loanId)).isEqualTo(1);
        assertThat(count("SELECT outstanding_cents FROM reserve.loan_exposure WHERE loan_id = ?", loanId)).isZero();
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, member))).isZero();
        assertIntegrity(loanId);
        assertLedgerBalanced();

        // delivering the return a second time hands nothing back twice
        long balanceAfter = reserve.summary().balanceCents();
        applications.onPayoutReturned(id.toString(), "RETURNED");
        settle();
        assertThat(reserve.summary().balanceCents()).isEqualTo(balanceAfter);
    }

    // ---- helpers ----

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

    /** Asks PayPal to pay, has PayPal confirm it, and returns the loan that registers. */
    private UUID registeredLoan(UUID applicationId) {
        try {
            disburse(applicationId, 200);
            payoutSucceededWebhook(paypal.payoutOfReference(applicationId.toString()));
            settle();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return jdbc.sql("SELECT id FROM lending.loan WHERE disbursement_ref = ?").param("loan-" + applicationId + "-disb")
            .query(UUID.class).single();
    }

    private JsonNode disburse(UUID applicationId, int expectedStatus) throws Exception {
        return read(mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", applicationId), OFFICER_TWO, "OFFICER"))
            .andExpect(status().is(expectedStatus)).andReturn());
    }

    private JsonNode application(UUID member, UUID applicationId) throws Exception {
        return read(mvc.perform(get("/api/v1/loans/applications/{id}", applicationId).header("X-Seed-Member-Id", member.toString()))
            .andExpect(status().isOk()).andReturn());
    }

    private long count(String sql, Object param) {
        return jdbc.sql(sql).param(param).query(Long.class).single();
    }

    private long journalEntries() {
        return jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry").query(Long.class).single();
    }

    private long recordFacts(UUID member) {
        // The void itself is one new fact (ADR 0025 follow-up); it is how the record learns the loan never was.
        return jdbc.sql("SELECT COUNT(*) FROM creditrecord.record_fact WHERE member_id = :m AND kind <> 'LOAN_VOIDED'")
            .param("m", member).query(Long.class).single();
    }

    private UUID coverApprovedByTwoOfficers(UUID member, UUID loan, long cents, String key) throws Exception {
        UUID maker = UUID.randomUUID();
        UUID checker = UUID.randomUUID();
        ResultActions created = mvc.perform(staff(post("/api/v1/desk/reserve/cover-requests"), maker, "OFFICER")
                .header("Idempotency-Key", "cover-" + key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":\"" + member + "\",\"loanId\":\"" + loan + "\",\"amountCents\":" + cents
                        + ",\"reasonCode\":\"INCOME_DROP_VERIFIED\"}"));
        created.andExpect(status().isCreated());
        UUID cover = UUID.fromString(json.readTree(created.andReturn().getResponse().getContentAsString()).path("id").asText());
        decide(cover, maker, "OFFICER", key + "-maker").andExpect(status().isOk());
        decide(cover, checker, "CHECKER", key + "-checker").andExpect(status().isOk())
                .andExpect(jsonPath("$.coverStatus").value("APPROVED"));
        return cover;
    }

    private ResultActions decide(UUID cover, UUID staffId, String roles, String key) throws Exception {
        return mvc.perform(staff(post("/api/v1/desk/reserve/cover-requests/{id}/decision", cover), staffId, roles)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"reasonCode\":\"INCOME_DROP_VERIFIED\",\"evidenceViewedMs\":30000}"));
    }

    private static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder request, UUID staffId, String roles) {
        return request.header("X-Seed-Staff-Id", staffId.toString()).header("X-Seed-Staff-Roles", roles);
    }
}
