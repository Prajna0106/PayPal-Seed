package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.HeldRefunds;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.infrastructure.persistence.HeldRefundRepository;
import com.paypalseed.lending.infrastructure.persistence.HeldRefundRepository.HeldRefund;
import com.paypalseed.lending.infrastructure.persistence.HeldRefundRepository.Status;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.PayPalWebhooks;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import java.time.LocalDate;
import java.util.UUID;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Money held for a member on a loan that never stood is refunded for certain (ADR 0024, closing pass): a refund PayPal
 * cannot make at once is kept as a pending row and retried by the worker's job, made exactly once, given up on after a
 * fixed number of tries with a governance entry for the desk and no message to the member, and never more than is held.
 * The same mechanism refunds what a member paid before cancelling in the cooling-off window, and a payment that reaches a
 * cancelled loan later.
 */
@TestPropertySource(properties = {"seed.lending.held-refund.max-attempts=3", "seed.lending.held-refund.retry-delay=PT1H"})
class HeldRefundIT extends MoneyFlowSupport {

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
    HeldRefunds heldRefunds;
    @Autowired
    HeldRefundRepository refunds;

    // ---- a payout that came back ----

    @Test
    void aRefundPayPalCannotMakeYetIsKeptRetriedByTheJobAndMadeOnce() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-HR-1", loanId, 30_000));
        settle();
        String reference = HeldRefunds.voidReference(loanId);

        paypal.failNext("refundCapture", FakePayPal.unavailable());
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();

        HeldRefund pending = refunds.find(reference).orElseThrow();
        assertThat(pending.status()).isEqualTo(Status.PENDING);
        assertThat(pending.attempts()).isEqualTo(1);
        assertThat(pending.amountCents()).isEqualTo(30_000);
        assertThat(pending.lastError()).isEqualTo("dependency-unavailable");
        assertThat(queries.loan(loanId).overpaidCents()).as("the member's money is still held, and visible").isEqualTo(30_000);
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, member))).isEqualTo(30_000);
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);

        // not due yet: the job leaves it alone
        assertThat(heldRefunds.retryDue()).isZero();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);

        clock.set(clock.today().plusDays(1));
        assertThat(heldRefunds.retryDue()).isEqualTo(1);
        settle();

        HeldRefund done = refunds.find(reference).orElseThrow();
        assertThat(done.status()).isEqualTo(Status.REFUNDED);
        assertThat(done.refundIds()).isNotBlank();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(2);
        assertThat(queries.loan(loanId).overpaidCents()).isZero();
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, member))).isZero();
        assertThat(queries.transactions(queries.loan(loanId))).extracting(t -> t.type()).containsExactly("RECEIPT", "OVERPAYMENT_REFUND");
        assertIntegrity(loanId);
        assertLedgerBalanced();

        // refunded once: more runs of the job, a replayed return and a re-asked attempt all do nothing
        clock.set(clock.today().plusDays(1));
        assertThat(heldRefunds.retryDue()).isZero();
        assertThat(heldRefunds.attempt(reference)).isEqualTo(HeldRefunds.Result.NOTHING_TO_DO);
        applications.onPayoutReturned(id.toString(), "RETURNED");
        settle();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM lending.loan_transaction WHERE loan_id = ? AND type = 'OVERPAYMENT_REFUND'", loanId))
                .isEqualTo(1);
    }

    @Test
    void aRefundThatKeepsFailingIsGivenUpOnTheDeskIsToldAndTheMemberHearsNothing() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-HR-2", loanId, 30_000));
        settle();
        String reference = HeldRefunds.voidReference(loanId);
        paypal.failNext("refundCapture", FakePayPal.unavailable(), FakePayPal.unavailable(), FakePayPal.unavailable(),
                FakePayPal.unavailable());

        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();
        long messages = count("SELECT COUNT(*) FROM engagement.reminder WHERE loan_id = ?", loanId);
        assertThat(refunds.find(reference).orElseThrow().attempts()).isEqualTo(1);

        clock.set(clock.today().plusDays(1));
        heldRefunds.retryDue();
        assertThat(refunds.find(reference).orElseThrow().status()).isEqualTo(Status.PENDING);
        assertThat(refunds.find(reference).orElseThrow().attempts()).isEqualTo(2);
        clock.set(clock.today().plusDays(1));
        heldRefunds.retryDue();
        settle();

        HeldRefund failed = refunds.find(reference).orElseThrow();
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(paypal.callCount("refundCapture")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM governance.audit_entry WHERE entry_type = 'HELD_REFUND_FAILED' AND subject_id = ?", loanId))
                .as("one entry for the desk").isEqualTo(1);
        assertThat(queries.loan(loanId).overpaidCents()).as("the money stays held and visible").isEqualTo(30_000);
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, member))).isEqualTo(30_000);
        assertThat(count("SELECT COUNT(*) FROM engagement.reminder WHERE loan_id = ?", loanId))
                .as("no message to the member about it").isEqualTo(messages);

        // terminal: neither the job nor a direct attempt asks PayPal again, and nothing is audited twice
        clock.set(clock.today().plusDays(3));
        assertThat(heldRefunds.retryDue()).isZero();
        assertThat(heldRefunds.attempt(reference)).isEqualTo(HeldRefunds.Result.NOTHING_TO_DO);
        assertThat(paypal.callCount("refundCapture")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM governance.audit_entry WHERE entry_type = 'HELD_REFUND_FAILED' AND subject_id = ?", loanId))
                .isEqualTo(1);
        assertLedgerBalanced();
    }

    @Test
    void aPaymentThatIsReversedBeforeTheRefundLeavesNothingToRefundAndAPartOfItIsNeverRefundedWhole() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-HR-3", loanId, 30_000));
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-HR-4", loanId, 20_000));
        settle();
        paypal.failNext("refundCapture", FakePayPal.unavailable());
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();
        String reference = HeldRefunds.voidReference(loanId);
        assertThat(refunds.find(reference).orElseThrow().amountCents()).isEqualTo(50_000);

        // PayPal takes 20,000 of the member's payments back before the refund is made: only 30,000 is held now
        webhook(PayPalWebhooks.captureRefunded(PayPalWebhooks.newEventId(), "RF-HR-4", "CAP-HR-4", 20_000));
        settle();
        assertThat(queries.loan(loanId).overpaidCents()).isEqualTo(30_000);

        clock.set(clock.today().plusDays(1));
        heldRefunds.retryDue();
        settle();

        HeldRefund row = refunds.find(reference).orElseThrow();
        assertThat(row.status()).as("never more than is held: a person looks at it").isEqualTo(Status.FAILED);
        assertThat(row.lastError()).isEqualTo("HELD_LESS_THAN_REFUND");
        assertThat(paypal.callCount("refundCapture")).as("PayPal was not asked for the 50,000").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM governance.audit_entry WHERE entry_type = 'HELD_REFUND_FAILED' AND subject_id = ?", loanId))
                .isEqualTo(1);
        assertThat(queries.loan(loanId).overpaidCents()).isEqualTo(30_000);
    }

    @Test
    void aMemberWhoTookTheirHeldMoneyThemselvesIsNotRefundedTwice() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-HR-5", loanId, 30_000));
        settle();
        paypal.failNext("refundCapture", FakePayPal.unavailable());
        payoutReturnedWebhook(paypal.payoutOfReference(id.toString()));
        settle();
        String reference = HeldRefunds.voidReference(loanId);
        assertThat(refunds.find(reference).orElseThrow().status()).isEqualTo(Status.PENDING);

        // the refund endpoint of a void loan still works for its member; here it takes everything that was held
        mvc.perform(asMember(post("/api/v1/loans/{id}/overpayment-refunds", loanId), member)
                .header("Idempotency-Key", "own-refund-" + loanId).content("{\"amountCents\":30000}")).andExpect(status().is2xxSuccessful());
        settle();
        assertThat(queries.loan(loanId).overpaidCents()).isZero();
        long calls = paypal.callCount("refundCapture");

        clock.set(clock.today().plusDays(1));
        heldRefunds.retryDue();

        assertThat(refunds.find(reference).orElseThrow().status()).isEqualTo(Status.REFUNDED);
        assertThat(paypal.callCount("refundCapture")).as("nothing more was asked of PayPal").isEqualTo(calls);
        assertThat(count("SELECT COUNT(*) FROM governance.audit_entry WHERE entry_type = 'HELD_REFUND_FAILED' AND subject_id = ?", loanId))
                .isZero();
    }

    // ---- the cooling-off cancel ----

    @Test
    void aMemberWhoPaidAndThenCancelledGetsTheirMoneyBackAndTheReceivableEndsAtZero() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        long cashBefore = programCash();
        UUID loanId = registeredLoan(id);
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-CO-1", loanId, 30_000));
        settle();
        assertThat(receivable(loanId)).isEqualTo(90_000);

        cancel(member, id);
        settle();

        var loan = queries.loan(loanId);
        assertThat(loan.status().name()).isEqualTo("CANCELLED");
        assertThat(loan.outstandingCents()).isZero();
        assertThat(receivable(loanId)).as("exactly zero, never negative").isZero();
        assertThat(loan.overpaidCents()).as("refunded, so nothing is held any more").isZero();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);
        assertThat(refunds.find(HeldRefunds.cancelReference(loanId)).orElseThrow().status()).isEqualTo(Status.REFUNDED);
        assertThat(queries.transactions(loan)).extracting(t -> t.type()).containsExactly("RECEIPT", "OVERPAYMENT_REFUND");
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, member))).isZero();
        assertThat(programCash()).as("every cent is back where it started").isEqualTo(cashBefore);
        assertIntegrity(loanId);
        assertLedgerBalanced();

        // cancelling again changes nothing and refunds nothing twice
        mvc.perform(asMember(post("/api/v1/loans/applications/{id}/cooling-off-cancel", id), member)).andExpect(status().isConflict());
        settle();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);
    }

    @Test
    void aMemberWhoRepaidInFullAndThenCancelledIsRefundedInFull() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        long cashBefore = programCash();
        UUID loanId = registeredLoan(id);
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-CO-2", loanId, 120_000));
        settle();
        assertThat(queries.loan(loanId).status().name()).isEqualTo("REPAID");

        cancel(member, id);
        settle();

        var loan = queries.loan(loanId);
        assertThat(loan.status().name()).isEqualTo("CANCELLED");
        assertThat(receivable(loanId)).isZero();
        assertThat(loan.overpaidCents()).isZero();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);
        assertThat(programCash()).isEqualTo(cashBefore);
        assertIntegrity(loanId);
        assertLedgerBalanced();
    }

    @Test
    void aCancelRefundPayPalCannotMakeYetIsRetriedLikeAnyOther() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-CO-3", loanId, 30_000));
        settle();

        paypal.failNext("refundCapture", FakePayPal.unavailable());
        cancel(member, id);
        settle();
        assertThat(refunds.find(HeldRefunds.cancelReference(loanId)).orElseThrow().status()).isEqualTo(Status.PENDING);
        assertThat(queries.loan(loanId).overpaidCents()).isEqualTo(30_000);

        clock.set(clock.today().plusDays(1));
        assertThat(heldRefunds.retryDue()).isEqualTo(1);
        settle();

        assertThat(refunds.find(HeldRefunds.cancelReference(loanId)).orElseThrow().status()).isEqualTo(Status.REFUNDED);
        assertThat(queries.loan(loanId).overpaidCents()).isZero();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(2);
        assertIntegrity(loanId);
        assertLedgerBalanced();
    }

    @Test
    void aPaymentThatReachesACancelledLoanLaterIsHeldAndRefundedNotCountedAsARepayment() throws Exception {
        UUID member = newMember();
        UUID id = approvedApplication(member);
        UUID loanId = registeredLoan(id);
        cancel(member, id);
        settle();
        assertThat(paypal.callCount("refundCapture")).isZero();

        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-CO-LATE", loanId, 15_000));
        settle();

        var loan = queries.loan(loanId);
        assertThat(loan.status().name()).isEqualTo("CANCELLED");
        assertThat(loan.outstandingCents()).isZero();
        assertThat(loan.overpaidCents()).isZero();
        assertThat(receivable(loanId)).isZero();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);
        assertThat(queries.transactions(loan)).extracting(t -> t.type()).containsExactly("RECEIPT", "OVERPAYMENT_REFUND");
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, member))).isZero();
        assertThat(count("SELECT COUNT(*) FROM lending.held_refund WHERE loan_id = ? AND status = 'REFUNDED'", loanId)).isEqualTo(1);
        assertIntegrity(loanId);
        assertLedgerBalanced();

        // the same payment delivered again is recorded once and refunded once
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-CO-LATE", loanId, 15_000));
        settle();
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);
    }

    // ---- helpers ----

    private void cancel(UUID member, UUID applicationId) throws Exception {
        mvc.perform(asMember(post("/api/v1/loans/applications/{id}/cooling-off-cancel", applicationId), member))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED_COOLING_OFF"));
    }

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
        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/disburse", applicationId), OFFICER_TWO, "OFFICER")
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
        payoutSucceededWebhook(paypal.payoutOfReference(applicationId.toString()));
        settle();
        return jdbc.sql("SELECT id FROM lending.loan WHERE disbursement_ref = ?").param("loan-" + applicationId + "-disb")
                .query(UUID.class).single();
    }

    private long count(String sql, Object param) {
        return jdbc.sql(sql).param(param).query(Long.class).single();
    }
}
