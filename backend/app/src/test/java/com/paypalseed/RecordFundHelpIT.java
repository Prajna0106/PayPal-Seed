package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentGrant;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.lending.api.dto.LendingDtos.CoverResult;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.ReserveCover;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.application.usecases.RepaymentService.Receipt;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Help from the safety fund and the credit record (ADR 0025), through the events lending really publishes: a cover
 * is a repayment with source {@code RESERVE}. It never counts against the member and it never lifts their record.
 */
@TestPropertySource(properties = "seed.record.signing-secret=" + RecordFundHelpIT.SECRET)
class RecordFundHelpIT extends IntegrationTest {

    static final String SECRET = "it-fund-help-signing-secret-0123456789abcdef";
    private static final String MEMBER = "X-Seed-Member-Id";
    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10);

    @Autowired
    ObjectMapper json;
    @Autowired
    CreditRecordFacade creditRecord;
    @Autowired
    RepaymentService repayments;
    @Autowired
    ReminderRepository contacts;
    @Autowired
    IdentityFacade identity;

    @Test
    void aLoanTheFundPaidInFullIsRepaidButDoesNotLiftTheRecord() throws Exception {
        UUID helped = savedForSixMonths();
        UUID own = savedForSixMonths();
        LoanSummary fundLoan = disburse(helped, 100, 4, 15, DISBURSED);
        LoanSummary ownLoan = disburse(own, 100, 4, 15, DISBURSED);
        settle();
        assertThat(level(helped)).isEqualTo("BUILDING");

        clock.set(LocalDate.of(2026, 12, 15));
        CoverResult cover = cover(fundLoan.loanId(), 10_000, "full");
        repayments.recordReceipt(new Receipt(ownLoan.loanId(), "own-" + ownLoan.loanId(), Source.CHECKOUT, 10_000, 0, Instant.now(), null, "it"));
        settle();

        assertThat(cover.appliedCents()).isEqualTo(10_000);
        assertThat(lending.loan(fundLoan.loanId()).orElseThrow().status()).isEqualTo("REPAID");

        // The helped member: repaid, never held against them, and nothing the fund paid is theirs.
        JsonNode view = recordOf(helped);
        assertThat(view.at("/loans/completedWithFundHelp").asInt()).isEqualTo(1);
        assertThat(view.at("/loans/repaidOnPlan").asInt()).isZero();
        assertThat(view.at("/loans/repaidOffPlan").asInt()).isZero();
        assertThat(view.at("/instalments/onTime").asInt()).isZero();
        assertThat(view.at("/instalments/late").asInt()).isZero();
        assertThat(view.at("/instalments/openLate").asInt()).isZero();
        assertThat(view.path("defaults").asInt()).isZero();
        assertThat(view.path("level").asText()).as("the fund's money does not lift the record").isEqualTo("BUILDING");
        assertThat(kinds(helped)).contains("INSTALMENT_FUND_COVERED", "LOAN_REPAID").doesNotContain("INSTALMENT_MISSED", "LOAN_DEFAULTED");

        // The member who paid the same loan themselves does move up.
        JsonNode ownView = recordOf(own);
        assertThat(ownView.at("/loans/repaidOnPlan").asInt()).isEqualTo(1);
        assertThat(ownView.at("/loans/completedWithFundHelp").asInt()).isZero();
        assertThat(ownView.path("level").asText()).isEqualTo("STEADY");

        // The partner is told honestly, with a count and no reason.
        String token = shareOf(helped);
        JsonNode verified = json.readTree(mvc.perform(get("/api/v1/records/verify/{token}", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(verified.path("loansRepaidOnPlan").asInt()).isZero();
        assertThat(verified.path("loansCompletedWithFundHelp").asInt()).isEqualTo(1);
        assertThat(verified.toString().toLowerCase()).doesNotContain("hardship").doesNotContain("reserve");
    }

    @Test
    void aMissTheFundCoveredIsNotHeldAgainstTheMemberWhoFinishesTheLoan() throws Exception {
        UUID ana = savedForSixMonths();
        LoanSummary loan = disburse(ana, 100, 4, 15, DISBURSED);
        contacts.upsertContact(ana, "+15550004001", "en", "America/Los_Angeles");
        settle();

        closeThrough(LocalDate.of(2026, 12, 22));          // the first instalment (15 December) ran out of grace
        assertThat(recordOf(ana).at("/instalments/openLate").asInt()).isEqualTo(1);

        clock.set(LocalDate.of(2026, 12, 23));
        cover(loan.loanId(), 2_500, "miss");
        settle();

        JsonNode afterCover = recordOf(ana);
        assertThat(afterCover.at("/instalments/openLate").asInt()).as("the fund paid what was missed").isZero();
        assertThat(afterCover.at("/instalments/late").asInt()).isZero();
        assertThat(afterCover.at("/instalments/onTime").asInt()).isZero();

        for (LocalDate due : List.of(LocalDate.of(2027, 1, 15), LocalDate.of(2027, 2, 15), LocalDate.of(2027, 3, 15))) {
            clock.set(due);
            repayments.recordReceipt(new Receipt(loan.loanId(), "own-" + due + "-" + loan.loanId(), Source.CHECKOUT, 2_500, 0, Instant.now(),
                    null, "it"));
            settle();
        }

        JsonNode done = recordOf(ana);
        assertThat(lending.loan(loan.loanId()).orElseThrow().status()).isEqualTo("REPAID");
        assertThat(done.at("/instalments/onTime").asInt()).as("only the three the member paid").isEqualTo(3);
        assertThat(done.at("/instalments/late").asInt()).isZero();
        assertThat(done.at("/instalments/openLate").asInt()).isZero();
        assertThat(done.at("/loans/repaidOnPlan").asInt()).as("the miss was the fund's to cover, not the member's").isEqualTo(1);
        assertThat(done.at("/loans/repaidOffPlan").asInt()).isZero();
        assertThat(done.at("/loans/completedWithFundHelp").asInt()).isZero();
        assertThat(done.path("defaults").asInt()).isZero();
    }

    @Test
    void anInstalmentTheFundPartPaidAndTheMemberFinishedIsTheMembersOwnAndLateByTheirOwnPaymentDate() throws Exception {
        UUID ana = savedForSixMonths();
        LoanSummary loan = disburse(ana, 100, 4, 15, DISBURSED);
        settle();

        clock.set(LocalDate.of(2026, 12, 10));
        cover(loan.loanId(), 1_000, "part");               // the fund pays 1,000 of the first 2,500
        settle();

        JsonNode afterCover = recordOf(ana);
        assertThat(afterCover.at("/instalments/onTime").asInt()).as("not finished, so not yet an instalment of anyone").isZero();
        assertThat(afterCover.at("/instalments/late").asInt()).isZero();
        assertThat(kinds(ana)).as("the fund did not pay the whole instalment").doesNotContain("INSTALMENT_FUND_COVERED", "INSTALMENT_PAID");

        clock.set(LocalDate.of(2026, 12, 22));             // seven days after the due date, past the five days of grace
        repayments.recordReceipt(new Receipt(loan.loanId(), "own-finish-" + loan.loanId(), Source.CHECKOUT, 1_500, 0, Instant.now(), null, "it"));
        settle();

        JsonNode finished = recordOf(ana);
        assertThat(finished.at("/instalments/late").asInt()).as("the member's own, late by the day they finished it").isEqualTo(1);
        assertThat(finished.at("/instalments/onTime").asInt()).isZero();
        assertThat(kinds(ana)).doesNotContain("INSTALMENT_FUND_COVERED");

        for (LocalDate due : List.of(LocalDate.of(2027, 1, 15), LocalDate.of(2027, 2, 15), LocalDate.of(2027, 3, 15))) {
            clock.set(due);
            repayments.recordReceipt(new Receipt(loan.loanId(), "own-" + due + "-" + loan.loanId(), Source.CHECKOUT, 2_500, 0, Instant.now(),
                    null, "it"));
            settle();
        }

        JsonNode done = recordOf(ana);
        assertThat(lending.loan(loan.loanId()).orElseThrow().status()).isEqualTo("REPAID");
        assertThat(done.at("/instalments/onTime").asInt()).isEqualTo(3);
        assertThat(done.at("/instalments/late").asInt()).isEqualTo(1);
        assertThat(done.at("/loans/repaidOnPlan").asInt()).as("the member finished the loan").isEqualTo(1);
        assertThat(done.at("/loans/completedWithFundHelp").asInt()).isZero();
        assertThat(done.at("/instalments/openLate").asInt()).isZero();
        assertThat(done.path("defaults").asInt()).isZero();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private CoverResult cover(UUID loanId, long cents, String key) {
        return lending.applyReserveCover(new ReserveCover(loanId, cents, "reserve-cover:" + key + "-" + loanId, UUID.randomUUID(),
                clock.today(), clock.now()));
    }

    /** Six saving months in the record: STEADY needs one completed loan more, so a loan decides the level. */
    private UUID savedForSixMonths() {
        UUID member = identity.loginWithPayPal(new PayPalLogin("PAYER-" + UUID.randomUUID(), "member@example.test", "Member", true, "en"))
                .member().memberId();
        for (int month = 1; month <= 6; month++) {
            creditRecord.ingest(RecordFact.savingsDeposit("fund-it-" + member + "-" + month, member, LocalDate.of(2026, month, 5)));
        }
        return member;
    }

    private void closeThrough(LocalDate date) throws Exception {
        clock.set(date);
        mvc.perform(post("/internal/jobs/cob/catch-up").param("to", date.toString()).header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk());
        settle();
    }

    private String shareOf(UUID member) throws Exception {
        identity.grantConsent(member, new ConsentGrant(ConsentType.RECORD_SHARING, null));
        String body = mvc.perform(post("/api/v1/members/me/record/shares").header(MEMBER, member.toString()).header("Idempotency-Key", "fund-help")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\":7,\"detail\":\"FULL\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("token").asText();
    }

    private JsonNode recordOf(UUID member) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/members/me/record").header(MEMBER, member.toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String level(UUID member) throws Exception {
        return recordOf(member).path("level").asText();
    }

    private List<String> kinds(UUID member) {
        return jdbc.sql("SELECT kind FROM creditrecord.record_fact WHERE member_id = ?").param(member).query(String.class).list();
    }
}
