package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.lending.api.dto.LendingDtos.CoverOutcome;
import com.paypalseed.lending.api.dto.LendingDtos.CoverResult;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.ReserveCover;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A reserve cover reaches the loan (ADR 0019, ADR 0020): two different officers approve it, reserve posts it to the
 * ledger once, and lending puts the same money on the schedule as a repayment from the reserve without posting again.
 * The credit record learns of it through the ordinary repayment event.
 */
class CoverRepaymentIT extends IntegrationTest {

    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 12, 15);

    @Autowired
    ReserveFacade reserve;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ObjectMapper json;

    @Test
    void anApprovedCoverPaysTheDueInstalmentOnTheScheduleAndTheLedgerIsPostedOnce() throws Exception {
        UUID member = UUID.randomUUID();
        fund(50_000);
        LoanSummary loan = disburse(member, 1_200, 8, 15, DISBURSED);
        settle();
        clock.set(FIRST_DUE);
        long receivableBefore = receivable(loan.loanId());
        long reserveBefore = reserveBalance();
        long entriesBefore = journalEntries();
        assertThat(receivableBefore).isEqualTo(120_000);

        UUID cover = coverApprovedByTwoOfficers(member, loan.loanId(), 20_000, "once");
        settle();

        // The schedule: what is due is paid first (the whole first instalment), the rest goes in advance to the last one.
        ScheduleView schedule = lending.currentSchedule(loan.loanId());
        InstalmentLine first = schedule.instalments().get(0);
        assertThat(first.paidCents()).isEqualTo(15_000);
        assertThat(first.remainingCents()).isZero();
        assertThat(first.status()).isEqualTo("PAID");
        assertThat(first.paidOn()).isEqualTo(FIRST_DUE);
        assertThat(schedule.instalments().get(7).paidCents()).as("the 5,000 over, in advance").isEqualTo(5_000);
        assertThat(schedule.outstandingCents()).isEqualTo(100_000);
        assertThat(lending.loan(loan.loanId()).orElseThrow().outstandingCents()).isEqualTo(100_000);
        assertThat(schedule.loanStatus()).isEqualTo("ACTIVE");

        // The money is recorded as the reserve's, once, with the entry reserve posted.
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT type, source, paypal_txn_id, amount_cents, fee_cents, journal_entry_id, effective_date
                  FROM lending.loan_transaction WHERE loan_id = :loan""").param("loan", loan.loanId()).query().listOfRows();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("type")).isEqualTo("RECEIPT");
            assertThat(row.get("source")).isEqualTo("RESERVE");
            assertThat(row.get("paypal_txn_id")).isEqualTo("reserve-cover:" + cover);
            assertThat(((Number) row.get("amount_cents")).longValue()).isEqualTo(20_000);
            assertThat(((Number) row.get("fee_cents")).longValue()).isZero();
            assertThat(row.get("journal_entry_id")).isEqualTo(coverEntry(cover));
            assertThat(row.get("effective_date").toString()).isEqualTo(FIRST_DUE.toString());
        });

        // The ledger: reserve posted once (debit the fund, credit the loan) and lending posted nothing. It balances,
        // and the loan's receivable still equals what the schedule says is owed.
        assertThat(reserveBefore - reserveBalance()).isEqualTo(20_000);
        assertThat(receivableBefore - receivable(loan.loanId())).isEqualTo(20_000);
        assertThat(journalEntries() - entriesBefore).as("reserve's one entry, nothing from lending").isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry WHERE source_key LIKE 'paypal:%reserve%'")
                .query(Integer.class).single()).isZero();
        assertJournalBalanced();
        assertThat(queries.integrity(loan.loanId()).consistent()).as("receivable equals the schedule's outstanding").isTrue();
        assertThat(queries.integrity(loan.loanId()).ledgerReceivableCents()).isEqualTo(100_000);

        // The credit record heard of the paid instalment through the normal repayment event.
        assertThat(jdbc.sql("SELECT inst_seq FROM creditrecord.record_fact WHERE member_id = :m AND kind = 'INSTALMENT_PAID'")
                .param("m", member).query(Integer.class).list()).containsExactly(1);
        assertThat(jdbc.sql("SELECT days_late FROM creditrecord.record_fact WHERE member_id = :m AND kind = 'INSTALMENT_PAID'")
                .param("m", member).query(Integer.class).single()).as("paid on the due date").isZero();
    }

    @Test
    void applyingTheSameApprovalAgainChangesNothing() throws Exception {
        UUID member = UUID.randomUUID();
        fund(50_000);
        LoanSummary loan = disburse(member, 1_200, 8, 15, DISBURSED);
        clock.set(FIRST_DUE);
        UUID cover = coverApprovedByTwoOfficers(member, loan.loanId(), 20_000, "replay");
        settle();
        long receivableAfter = receivable(loan.loanId());
        long entries = journalEntries();
        long factsBefore = factCount(member);

        CoverResult replay = lending.applyReserveCover(new ReserveCover(loan.loanId(), 20_000, "reserve-cover:" + cover,
                coverEntry(cover), FIRST_DUE, clock.now()));
        settle();

        assertThat(replay.outcome()).isEqualTo(CoverOutcome.ALREADY_APPLIED);
        assertThat(replay.appliedCents()).isEqualTo(20_000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_transaction WHERE loan_id = :l").param("l", loan.loanId())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(lending.currentSchedule(loan.loanId()).outstandingCents()).isEqualTo(100_000);
        assertThat(receivable(loan.loanId())).isEqualTo(receivableAfter);
        assertThat(journalEntries()).isEqualTo(entries);
        assertThat(factCount(member)).isEqualTo(factsBefore);
    }

    @Test
    void aCoverThatPaysTheWholeLoanClosesItAndTheBooksStillAgree() throws Exception {
        UUID member = UUID.randomUUID();
        fund(50_000);
        LoanSummary loan = disburse(member, 100, 4, 15, DISBURSED);
        settle();

        coverApprovedByTwoOfficers(member, loan.loanId(), 10_000, "whole");
        settle();

        assertThat(lending.currentSchedule(loan.loanId()).outstandingCents()).isZero();
        assertThat(lending.loan(loan.loanId()).orElseThrow().status()).isEqualTo("REPAID");
        assertThat(receivable(loan.loanId())).isZero();
        assertThat(queries.integrity(loan.loanId()).consistent()).isTrue();
        assertThat(queries.integrity(loan.loanId()).overpaidCents()).as("the fund's money never becomes the member's").isZero();
        assertJournalBalanced();
    }

    @Test
    void aCoverForALoanThatOwesNothingIsRecordedAsNothingDue() throws Exception {
        UUID member = UUID.randomUUID();
        fund(50_000);
        LoanSummary loan = disburse(member, 100, 4, 15, DISBURSED);
        coverApprovedByTwoOfficers(member, loan.loanId(), 10_000, "paid-first");
        settle();
        assertThat(lending.loan(loan.loanId()).orElseThrow().status()).isEqualTo("REPAID");

        CoverResult late = lending.applyReserveCover(new ReserveCover(loan.loanId(), 5_000, "reserve-cover:" + UUID.randomUUID(),
                UUID.randomUUID(), DISBURSED, clock.now()));

        assertThat(late.outcome()).isEqualTo(CoverOutcome.NOTHING_DUE);
        assertThat(late.appliedCents()).isZero();
        assertThat(late.unusedCents()).isEqualTo(5_000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_transaction WHERE loan_id = :l").param("l", loan.loanId())
                .query(Integer.class).single()).isEqualTo(1);
    }

    // ---- helpers ----

    private void fund(long cents) {
        tx.execute(s -> reserve.recordDonation("CAP-fund-" + UUID.randomUUID(), cents, 0, null, true));
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

    private UUID coverEntry(UUID cover) {
        return jdbc.sql("SELECT id FROM ledger.journal_entry WHERE source_key = :k").param("k", "reserve-cover:" + cover)
                .query(UUID.class).single();
    }

    private long reserveBalance() {
        return ledger.balanceCents(AccountRef.program(AccountType.LOSS_RESERVE));
    }

    private long journalEntries() {
        return jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry").query(Long.class).single();
    }

    private long factCount(UUID member) {
        return jdbc.sql("SELECT COUNT(*) FROM creditrecord.record_fact WHERE member_id = :m").param("m", member)
                .query(Long.class).single();
    }

    private void assertJournalBalanced() {
        Long difference = jdbc.sql("SELECT COALESCE(SUM(CASE side WHEN 'DEBIT' THEN amount_cents ELSE -amount_cents END), 0) FROM ledger.journal_line")
                .query(Long.class).single();
        assertThat(difference).as("every debit has its credit").isZero();
    }
}
