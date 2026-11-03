package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverReturn;
import com.paypalseed.reserve.api.events.ReserveEvents.CoverApproved;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The race ADR 0020 left open, closed by ADR 0024: the member pays between the moment reserve posts a cover in the
 * ledger and the moment lending applies it. The ledger then credited the loan for more than the schedule could take;
 * the excess goes back to the fund with a new entry, once, and the fund, the books and the schedule agree again.
 */
class CoverReturnIT extends IntegrationTest {

    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 12, 15);

    @Autowired
    ReserveFacade reserve;
    @Autowired
    RepaymentService repayments;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ObjectMapper json;
    @Autowired
    ApplicationEventPublisher publisher;

    @Test
    void aPaymentThatLandsBeforeTheCoverEventLeavesTheExcessToGoBackToTheFund() throws Exception {
        UUID member = UUID.randomUUID();
        fund(50_000);
        LoanSummary loan = disburse(member, 100, 4, 15, DISBURSED);
        settle();
        clock.set(FIRST_DUE);
        long fundBefore = reserve.summary().balanceCents();
        long paidOutBefore = reserve.summary().totalPaidOutCents();
        long reserveLedgerBefore = ledger.balanceCents(AccountRef.program(AccountType.LOSS_RESERVE));
        UUID checker = UUID.randomUUID();
        UUID cover = coverAwaitingTheCheckersSignature(member, loan.loanId(), 10_000, "race", checker);

        // The checker's signature posts the cover to the ledger and queues the event; the member's payment of 60.00
        // commits in the same moment, before the event is delivered.
        tx.execute(s -> {
            try {
                decide(cover, checker, "CHECKER", "race-checker").andExpect(status().isOk())
                        .andExpect(jsonPath("$.coverStatus").value("APPROVED"));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            repayments.recordReceipt(new RepaymentService.Receipt(loan.loanId(), "PAY-RACE-1", Source.CHECKOUT, 6_000, 0,
                    Instant.now(), null, "it"));
            return null;
        });
        settle();

        // The schedule took what was still owed: 40.00 from the fund, 60.00 from the member, nothing over.
        var after = queries.loan(loan.loanId());
        assertThat(after.status().name()).isEqualTo("REPAID");
        assertThat(after.outstandingCents()).isZero();
        assertThat(after.overpaidCents()).as("the fund's money never becomes the member's").isZero();
        assertThat(jdbc.sql("SELECT amount_cents FROM lending.loan_transaction WHERE loan_id = :l AND source = 'RESERVE'")
                .param("l", loan.loanId()).query(Long.class).single()).isEqualTo(4_000);

        // The ledger: the 60.00 the cover credited too much went back with a new entry. The receivable is exactly zero,
        // the fund holds the 60.00 again, and the public figure agrees with the books.
        assertThat(receivable(loan.loanId())).isZero();
        assertThat(ledger.balanceCents(AccountRef.program(AccountType.LOSS_RESERVE))).isEqualTo(reserveLedgerBefore - 4_000);
        assertThat(reserve.summary().balanceCents()).isEqualTo(fundBefore - 4_000);
        assertThat(reserve.summary().totalPaidOutCents() - paidOutBefore).isEqualTo(4_000);
        assertThat(queries.integrity(loan.loanId()).consistent()).isTrue();
        assertJournalBalanced();

        // The return is recorded once, against the cover, with its entry, its event and its audit entry.
        assertThat(jdbc.sql("SELECT amount_cents FROM reserve.cover_return WHERE return_ref = :r")
                .param("r", "reserve-cover-return:reserve-cover:" + cover).query(Long.class).single()).isEqualTo(6_000);
        assertThat(jdbc.sql("SELECT kind FROM reserve.cover_return WHERE loan_id = :l").param("l", loan.loanId())
                .query(String.class).single()).isEqualTo("UNUSED_COVER");
        assertThat(jdbc.sql("SELECT returned_cents FROM reserve.cover_request WHERE id = :c").param("c", cover)
                .query(Long.class).single()).isEqualTo(6_000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry WHERE source_key = :k")
                .param("k", "reserve-cover-return:reserve-cover:" + cover).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM governance.audit_entry WHERE entry_type = 'RESERVE_COVER_RETURNED' AND subject_id = :c")
                .param("c", cover).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void deliveringTheCoverEventAgainReturnsNothingMore() throws Exception {
        UUID member = UUID.randomUUID();
        fund(50_000);
        LoanSummary loan = disburse(member, 100, 4, 15, DISBURSED);
        settle();
        clock.set(FIRST_DUE);
        UUID checker = UUID.randomUUID();
        UUID cover = coverAwaitingTheCheckersSignature(member, loan.loanId(), 10_000, "replay", checker);
        tx.execute(s -> {
            try {
                decide(cover, checker, "CHECKER", "replay-checker").andExpect(status().isOk());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            repayments.recordReceipt(new RepaymentService.Receipt(loan.loanId(), "PAY-RACE-2", Source.CHECKOUT, 6_000, 0,
                    Instant.now(), null, "it"));
            return null;
        });
        settle();
        long fundAfter = reserve.summary().balanceCents();
        long entries = journalEntries();

        UUID entry = jdbc.sql("SELECT id FROM ledger.journal_entry WHERE source_key = :k").param("k", "reserve-cover:" + cover)
                .query(UUID.class).single();
        tx.execute(s -> {
            publisher.publishEvent(new CoverApproved(UUID.randomUUID(), cover, loan.loanId(), member, 10_000, entry,
                    "reserve-cover:" + cover, FIRST_DUE));
            return null;
        });
        settle();

        assertThat(reserve.summary().balanceCents()).isEqualTo(fundAfter);
        assertThat(journalEntries()).isEqualTo(entries);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reserve.cover_return WHERE loan_id = :l").param("l", loan.loanId())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_transaction WHERE loan_id = :l AND source = 'RESERVE'")
                .param("l", loan.loanId()).query(Long.class).single()).isEqualTo(1);
        assertJournalBalanced();
    }

    @Test
    void aMemberWhoPaidTheWholeLoanFirstGetsTheWholeCoverBackToTheFund() throws Exception {
        UUID member = UUID.randomUUID();
        fund(50_000);
        LoanSummary loan = disburse(member, 100, 4, 15, DISBURSED);
        settle();
        clock.set(FIRST_DUE);
        long fundBefore = reserve.summary().balanceCents();
        long paidOutBefore = reserve.summary().totalPaidOutCents();
        UUID checker = UUID.randomUUID();
        UUID cover = coverAwaitingTheCheckersSignature(member, loan.loanId(), 5_000, "whole-back", checker);

        tx.execute(s -> {
            try {
                decide(cover, checker, "CHECKER", "whole-back-checker").andExpect(status().isOk());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            repayments.recordReceipt(new RepaymentService.Receipt(loan.loanId(), "PAY-RACE-3", Source.CHECKOUT, 10_000, 0,
                    Instant.now(), null, "it"));
            return null;
        });
        settle();

        assertThat(queries.loan(loan.loanId()).status().name()).isEqualTo("REPAID");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_transaction WHERE loan_id = :l AND source = 'RESERVE'")
                .param("l", loan.loanId()).query(Long.class).single()).as("nothing was left to cover").isZero();
        assertThat(receivable(loan.loanId())).isZero();
        assertThat(reserve.summary().balanceCents()).isEqualTo(fundBefore);
        assertThat(reserve.summary().totalPaidOutCents()).isEqualTo(paidOutBefore);
        assertThat(jdbc.sql("SELECT returned_cents FROM reserve.cover_request WHERE id = :c").param("c", cover)
                .query(Long.class).single()).isEqualTo(5_000);
        assertThat(queries.integrity(loan.loanId()).consistent()).isTrue();
        assertJournalBalanced();
    }

    @Test
    void aReturnIsRefusedWhenItIsMoreThanTheCoverPaidOrNotACoverAtAll() throws Exception {
        UUID member = UUID.randomUUID();
        fund(50_000);
        LoanSummary loan = disburse(member, 100, 4, 15, DISBURSED);
        settle();
        long paidOutBefore = reserve.summary().totalPaidOutCents();
        UUID cover = coverApprovedByTwoOfficers(member, loan.loanId(), 5_000, "refuse");
        settle();
        String ref = "reserve-cover:" + cover;

        assertThatThrownBy(() -> tx.execute(s -> reserve.returnUnusedCover(ref, 5_001, "LOAN_OWED_LESS")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("cover-return-exceeds-cover"));
        assertThatThrownBy(() -> tx.execute(s -> reserve.returnUnusedCover("loan-void:" + loan.loanId(), 100, "LOAN_OWED_LESS")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("cover-return-invalid"));
        assertThatThrownBy(() -> tx.execute(s -> reserve.returnUnusedCover(ref, 100, "lower case")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("cover-return-invalid"));
        assertThatThrownBy(() -> tx.execute(s -> reserve.returnUnusedCover("reserve-cover:" + UUID.randomUUID(), 100, "LOAN_OWED_LESS")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("cover-request-not-found"));

        // a good return is recorded once; the same again is the same answer; another amount under it is refused
        CoverReturn first = tx.execute(s -> reserve.returnUnusedCover(ref, 1_000, "LOAN_OWED_LESS"));
        CoverReturn again = tx.execute(s -> reserve.returnUnusedCover(ref, 1_000, "LOAN_OWED_LESS"));
        assertThat(first.replay()).isFalse();
        assertThat(again.replay()).isTrue();
        assertThat(again.journalEntryId()).isEqualTo(first.journalEntryId());
        assertThatThrownBy(() -> tx.execute(s -> reserve.returnUnusedCover(ref, 2_000, "LOAN_OWED_LESS")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("idempotency-key-reused"));
        assertThat(reserve.summary().totalPaidOutCents() - paidOutBefore).isEqualTo(4_000);
    }

    // ---- helpers ----

    private void fund(long cents) {
        tx.execute(s -> reserve.recordDonation("CAP-ret-" + UUID.randomUUID(), cents, 0, null, true));
    }

    /** The cover is created and the maker has signed; the checker has not. */
    private UUID coverAwaitingTheCheckersSignature(UUID member, UUID loan, long cents, String key, UUID checker) throws Exception {
        UUID maker = UUID.randomUUID();
        ResultActions created = mvc.perform(staff(post("/api/v1/desk/reserve/cover-requests"), maker, "OFFICER")
                .header("Idempotency-Key", "cover-" + key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":\"" + member + "\",\"loanId\":\"" + loan + "\",\"amountCents\":" + cents
                        + ",\"reasonCode\":\"INCOME_DROP_VERIFIED\"}"));
        created.andExpect(status().isCreated());
        UUID cover = UUID.fromString(json.readTree(created.andReturn().getResponse().getContentAsString()).path("id").asText());
        decide(cover, maker, "OFFICER", key + "-maker").andExpect(status().isOk());
        return cover;
    }

    private UUID coverApprovedByTwoOfficers(UUID member, UUID loan, long cents, String key) throws Exception {
        UUID checker = UUID.randomUUID();
        UUID cover = coverAwaitingTheCheckersSignature(member, loan, cents, key, checker);
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

    private long journalEntries() {
        return jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry").query(Long.class).single();
    }

    private void assertJournalBalanced() {
        Long difference = jdbc.sql("SELECT COALESCE(SUM(CASE side WHEN 'DEBIT' THEN amount_cents ELSE -amount_cents END), 0) FROM ledger.journal_line")
                .query(Long.class).single();
        assertThat(difference).as("every debit has its credit").isZero();
    }
}
