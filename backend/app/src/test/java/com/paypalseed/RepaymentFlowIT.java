package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/** FR-06..FR-09, FR-15: the money funnel end to end, with the ledger checked after every step. */
class RepaymentFlowIT extends IntegrationTest {

    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10);

    @Autowired
    ObjectMapper json;
    @Autowired
    RepaymentService repayments;
    @Autowired
    TransactionTemplate tx;

    private LoanSummary anaLoan() {
        return disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
    }

    private ScheduleView schedule(UUID loanId) {
        return lending.currentSchedule(loanId);
    }

    @Test
    void tcGen07_registeringADisbursedLoanCreatesVersion1AndBooksTheReceivable() {
        RegisterDisbursedLoan command = new RegisterDisbursedLoan(UUID.randomUUID(), 120_000, 8, 9, DISBURSED, "payout-ana-1");
        clock.set(DISBURSED);
        LoanSummary loan = lending.registerDisbursedLoan(command);
        LoanSummary again = lending.registerDisbursedLoan(command);

        assertThat(again.loanId()).isEqualTo(loan.loanId());
        ScheduleView schedule = schedule(loan.loanId());
        assertThat(schedule.version()).isEqualTo(1);
        assertThat(schedule.instalments()).hasSize(8).allSatisfy(i -> assertThat(i.amountCents()).isEqualTo(15_000));
        assertThat(schedule.instalments().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 12, 9));
        assertThat(receivable(loan.loanId())).isEqualTo(120_000);
        assertIntegrity(loan.loanId());
        settle();
    }

    @Test
    void tcIng01_tcIng07_checkoutPaymentPostsGrossFeeAndNetAndPaysTheInstalment() throws Exception {
        LoanSummary loan = anaLoan();
        UUID member = loan.memberId();
        clock.set(LocalDate.of(2026, 12, 9));
        paypal.feeCents = 485;
        long cashBefore = programCash();
        long feesBefore = ledger.balanceCents(AccountRef.program(AccountType.PAYPAL_FEES));

        String created = mvc.perform(post("/api/v1/loans/{id}/payments", loan.loanId())
                        .header("X-Seed-Member-Id", member.toString()).header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":15000,\"purpose\":\"EARLY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = json.readTree(created).path("orderId").asText();
        mvc.perform(post("/api/v1/loans/{id}/payments/{order}/capture", loan.loanId(), orderId)
                        .header("X-Seed-Member-Id", member.toString()))
                .andExpect(status().isOk());
        settle();

        InstalmentLine first = schedule(loan.loanId()).instalments().get(0);
        assertThat(first.status()).isEqualTo("PAID");
        assertThat(receivable(loan.loanId())).isEqualTo(105_000);
        assertThat(programCash() - cashBefore).isEqualTo(15_000 - 485);
        assertThat(ledger.balanceCents(AccountRef.program(AccountType.PAYPAL_FEES)) - feesBefore).isEqualTo(485);
        assertIntegrity(loan.loanId());
    }

    @Test
    void tcIng03_tcIng04_duplicateDeliveriesAndTwoEventsForOnePaymentPostOnce() throws Exception {
        LoanSummary loan = anaLoan();
        clock.set(LocalDate.of(2026, 12, 9));
        String eventId = PayPalWebhooks.newEventId();
        webhook(PayPalWebhooks.captureCompleted(eventId, "CAP-DUP-1", loan.loanId(), 15_000));
        webhook(PayPalWebhooks.captureCompleted(eventId, "CAP-DUP-1", loan.loanId(), 15_000));        // same delivery
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-DUP-1", loan.loanId(), 15_000)); // same money
        settle();

        assertThat(queries.transactions(queries.loan(loan.loanId()))).hasSize(1);
        assertThat(receivable(loan.loanId())).isEqualTo(105_000);
        assertIntegrity(loan.loanId());
    }

    @Test
    void tcIng08_tcRef03_overpaymentOnARepaidLoanIsRefundedAutomatically() throws Exception {
        LoanSummary loan = anaLoan();
        clock.set(LocalDate.of(2026, 12, 9));
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-OVER-1", loan.loanId(), 125_000));
        settle();

        var after = queries.loan(loan.loanId());
        assertThat(after.status().name()).isEqualTo("REPAID");
        assertThat(after.overpaidCents()).isZero();                 // the $50 went straight back
        assertThat(paypal.callCount("refundCapture")).isEqualTo(1);
        assertThat(queries.transactions(after)).extracting(t -> t.type())
                .containsExactly("RECEIPT", "OVERPAYMENT_REFUND");
        assertThat(ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, loan.memberId()))).isZero();
        assertIntegrity(loan.loanId());
    }

    @Test
    void tcIng09_reversalReopensARepaidLoan() throws Exception {
        LoanSummary loan = anaLoan();
        clock.set(LocalDate.of(2026, 12, 9));
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-FULL-1", loan.loanId(), 120_000));
        settle();
        assertThat(queries.loan(loan.loanId()).status().name()).isEqualTo("REPAID");

        webhook(PayPalWebhooks.captureRefunded(PayPalWebhooks.newEventId(), "RF-MEMBER-1", "CAP-FULL-1", 15_000));
        settle();

        var reopened = queries.loan(loan.loanId());
        assertThat(reopened.status().name()).isEqualTo("ACTIVE");
        assertThat(reopened.outstandingCents()).isEqualTo(15_000);
        assertThat(receivable(loan.loanId())).isEqualTo(15_000);
        assertIntegrity(loan.loanId());
    }

    @Test
    void refundArrivingBeforeItsPaymentIsParkedThenApplied() throws Exception {
        LoanSummary loan = anaLoan();
        clock.set(LocalDate.of(2026, 12, 9));
        webhook(PayPalWebhooks.captureRefunded(PayPalWebhooks.newEventId(), "RF-EARLY-1", "CAP-LATE-1", 5_000));
        inbox.drain(10);
        assertThat(jdbc.sql("SELECT status FROM payments.webhook_inbox WHERE event_type = 'PAYMENT.CAPTURE.REFUNDED'")
                .query(String.class).list()).contains("PARKED");

        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-LATE-1", loan.loanId(), 15_000));
        jdbc.sql("UPDATE payments.webhook_inbox SET next_attempt_at = now() WHERE status = 'PARKED'").update();
        settle();

        assertThat(queries.loan(loan.loanId()).outstandingCents()).isEqualTo(120_000 - 15_000 + 5_000);
        assertIntegrity(loan.loanId());
    }

    @Test
    void tcIng10_concurrentReceiptsAndDuplicatesOnOneLoanAreSerialised() throws Exception {
        LoanSummary loan = anaLoan();
        clock.set(LocalDate.of(2026, 12, 9));
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Callable<RepaymentService.RecordResult>> work = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String txn = "CONC-" + (i % 20);          // 10 of the 30 are duplicates of another
            work.add(() -> repayments.recordReceipt(new RepaymentService.Receipt(loan.loanId(), txn, Source.CHECKOUT,
                    1_000, 0, Instant.now(), null, "test")));
        }
        List<RepaymentService.RecordResult> results = new ArrayList<>();
        for (Future<RepaymentService.RecordResult> f : pool.invokeAll(work)) {
            results.add(f.get());
        }
        pool.shutdown();
        settle();

        assertThat(results).filteredOn(r -> r == RepaymentService.RecordResult.RECORDED).hasSize(20);
        assertThat(queries.loan(loan.loanId()).outstandingCents()).isEqualTo(120_000 - 20 * 1_000);
        assertIntegrity(loan.loanId());
    }

    /** Chaos C1: PayPal re-delivers the same webhook ten times at once. */
    @Test
    void c1_duplicateWebhookStormPostsOnce() throws Exception {
        LoanSummary loan = anaLoan();
        clock.set(LocalDate.of(2026, 12, 9));
        String body = PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-STORM", loan.loanId(), 15_000);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<Void>> deliveries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            deliveries.add(() -> {
                webhook(body);
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(deliveries)) {
            f.get();
        }
        pool.shutdown();
        settle();

        assertThat(queries.transactions(queries.loan(loan.loanId()))).hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry WHERE source_key = 'paypal:CAP-STORM'")
                .query(Integer.class).single()).isEqualTo(1);
        assertIntegrity(loan.loanId());
    }

    @Test
    void ledgerIsAppendOnlyAndRejectsUnbalancedEntries() {
        anaLoan();
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger.journal_line SET amount_cents = 1").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger.journal_entry").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID entry = UUID.randomUUID();
            UUID account = jdbc.sql("SELECT id FROM ledger.account WHERE type = 'PROGRAM_CASH'").query(UUID.class).single();
            jdbc.sql("INSERT INTO ledger.journal_entry (id, source_key, request_hash, description, business_date) "
                    + "VALUES (:id, 'bad', 'h', 'one-sided', CURRENT_DATE)").param("id", entry).update();
            jdbc.sql("INSERT INTO ledger.journal_line (entry_id, account_id, side, amount_cents) VALUES (:e, :a, 'DEBIT', 100)")
                    .param("e", entry).param("a", account).update();
        })).hasMessageContaining("not balanced");
    }

    @Test
    void scheduleVersionsMustSumToThePrincipal() {
        LoanSummary loan = anaLoan();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.sql("INSERT INTO lending.schedule_version (loan_id, version, reason, principal_cents, instalment_count, "
                    + "policy_version, caused_by, created_on) VALUES (:l, 2, 'CORRECTION', 120000, 1, 'repayment-v1', 'test', CURRENT_DATE)")
                    .param("l", loan.loanId()).update();
            jdbc.sql("INSERT INTO lending.instalment (loan_id, version, seq, due_date, amount_cents) VALUES (:l, 2, 1, CURRENT_DATE, 100000)")
                    .param("l", loan.loanId()).update();
        })).hasMessageContaining("inconsistent");
        assertThatThrownBy(() -> jdbc.sql("UPDATE lending.instalment SET amount_cents = 1").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void transactionsListShowsEveryMoneyEvent() throws Exception {
        LoanSummary loan = anaLoan();
        clock.set(LocalDate.of(2026, 12, 9));
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-LIST-1", loan.loanId(), 15_000));
        settle();
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/loans/{id}/transactions", loan.loanId()).header("X-Seed-Member-Id", loan.memberId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode list = json.readTree(body);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("paypalTxnId").asText()).isEqualTo("CAP-LIST-1");
    }
}
