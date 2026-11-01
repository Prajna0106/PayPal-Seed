package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.ApprovalView;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decide;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decision;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.support.PayPalWebhooks;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.reserve.api.dto.ReserveDtos.DonationReceipt;
import com.paypalseed.reserve.api.dto.ReserveDtos.ReserveSummary;
import com.paypalseed.reserve.api.events.ReserveEvents.CoverApproved;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The community safety fund end to end: gifts, the public summary, and cover requests that two different officers must
 * approve before the ledger moves money from the fund to a loan. The first test runs on an empty fund on purpose.
 */
@RecordApplicationEvents
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReserveIT extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired
    ReserveFacade reserve;
    @Autowired
    GovernanceFacade governance;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ObjectMapper json;
    @Autowired
    ApplicationEvents published;

    @Test
    @Order(1)
    void aCoverIsRefusedWhenTheFundCannotPayItAtRequestAndAtApproval() throws Exception {
        clock.set(DAY);
        assertThat(reserve.summary().balanceCents()).as("this test needs an empty fund").isZero();
        donate("CAP-seed-" + UUID.randomUUID(), 6_000, 0, null, true);

        UUID memberA = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        LoanSummary loanA = disburse(memberA, 100, 4, 15, DAY);
        LoanSummary loanB = disburse(memberB, 100, 4, 15, DAY);
        UUID maker = UUID.randomUUID();
        UUID checker = UUID.randomUUID();

        UUID first = created(createCover(maker, "OFFICER", memberA, loanA.loanId(), 5_000, "cap-1"));
        UUID second = created(createCover(maker, "OFFICER", memberB, loanB.loanId(), 4_000, "cap-2"));
        createCover(maker, "OFFICER", memberB, loanB.loanId(), 6_001, "cap-3")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("reserve-insufficient-funds"))
                .andExpect(jsonPath("$.details.balanceCents").value(6_000));

        // The first cover takes 5,000; the second was fine when asked but the fund no longer holds 4,000.
        approveBoth(first, maker, checker, "cap-1");
        assertThat(reserve.summary().balanceCents()).isEqualTo(1_000);
        decide(second, maker, "OFFICER", "APPROVE", "cap-2-maker").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reserve-insufficient-funds"));
        assertThat(coverStatus(second)).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM governance.approval a JOIN reserve.cover_request c ON c.approval_request_id = a.request_id WHERE c.id = :id")
                .param("id", second).query(Integer.class).single()).as("no signature was recorded").isZero();

        donate("CAP-topup-" + UUID.randomUUID(), 3_000, 0, null, true);
        approveBoth(second, maker, checker, "cap-2");
        assertThat(reserve.summary().balanceCents()).isZero();
        assertThat(reserve.summary().totalPaidOutCents()).isEqualTo(9_000);
        assertThat(reserve.summary().membersCovered()).isEqualTo(2);
        assertFundMatchesLedger();
        assertJournalBalanced();
    }

    @Test
    @Order(12)
    void aDonationCapturedAtPayPalReachesTheFundThroughTheWebhookExactlyOnce() throws Exception {
        long fundBefore = reserveBalance();
        long cashBefore = programCash();
        long feesBefore = ledger.balanceCents(AccountRef.program(AccountType.PAYPAL_FEES));
        long giftsBefore = reserve.summary().donationCount();

        JsonNode intent = json.readTree(mvc.perform(post("/api/v1/reserve/donations/intent").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "intent-hook-" + UUID.randomUUID())
                        .content("{\"anonymous\":false,\"displayName\":\"Rosa Webhook\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String customId = intent.path("customId").asText();
        String capture = "CAP-HOOK-" + UUID.randomUUID();

        webhook(donationCapture(PayPalWebhooks.newEventId(), capture, customId, 2_500, 100));
        settle();

        assertThat(reserveBalance() - fundBefore).as("the fund holds the gross gift").isEqualTo(2_500);
        assertThat(programCash() - cashBefore).as("cash is the gift less PayPal's fee").isEqualTo(2_400);
        assertThat(ledger.balanceCents(AccountRef.program(AccountType.PAYPAL_FEES)) - feesBefore).isEqualTo(100);
        assertThat(reserve.summary().donationCount() - giftsBefore).isEqualTo(1);
        assertThat(reserve.summary().supporters()).as("the donor chose to be shown").contains("Rosa Webhook");
        assertThat(jdbc.sql("SELECT anonymous FROM reserve.donation WHERE capture_id = :c").param("c", capture).query(Boolean.class).single())
                .isFalse();

        // PayPal sends the same capture again under a new webhook id, and the same webhook id twice: still one gift.
        webhook(donationCapture(PayPalWebhooks.newEventId(), capture, customId, 2_500, 100));
        String repeated = PayPalWebhooks.newEventId();
        webhook(donationCapture(repeated, capture + "-other", customId, 700, 0));
        webhook(donationCapture(repeated, capture + "-other", customId, 700, 0));
        settle();
        assertThat(reserveBalance() - fundBefore).isEqualTo(2_500 + 700);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reserve.donation WHERE capture_id = :c").param("c", capture).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry WHERE source_key = :k").param("k", "reserve-donation:" + capture)
                .query(Integer.class).single()).isEqualTo(1);

        // A custom id of ours that names no known intent is still a gift (the money arrived), and anonymous.
        String stranger = "CAP-HOOK-" + UUID.randomUUID();
        webhook(donationCapture(PayPalWebhooks.newEventId(), stranger, "reserve-donation:" + UUID.randomUUID(), 1_000, 0));
        settle();
        assertThat(jdbc.sql("SELECT anonymous FROM reserve.donation WHERE capture_id = :c").param("c", stranger).query(Boolean.class).single())
                .isTrue();
        assertThat(reserveBalance() - fundBefore).isEqualTo(2_500 + 700 + 1_000);

        assertFundMatchesLedger();
        assertJournalBalanced();
    }

    @Test
    @Order(13)
    void aCaptureThatCannotBeAGiftIsDroppedWithoutBlockingTheEventLog() throws Exception {
        long fundBefore = reserveBalance();
        String customId = "reserve-donation:" + UUID.randomUUID();

        // PayPal's fee is as large as the gift: there is nothing to put in the fund.
        webhook(donationCapture(PayPalWebhooks.newEventId(), "CAP-HOOK-FEE-" + UUID.randomUUID(), customId, 100, 100));
        settle();   // would fail with "events never completed" if the listener kept throwing

        assertThat(reserveBalance()).isEqualTo(fundBefore);
        assertFundMatchesLedger();
    }

    @Test
    @Order(2)
    void aDonationIsRecordedOnceAndPostedToTheLedger() {
        long reserveBefore = reserveBalance();
        long cashBefore = programCash();
        long feesBefore = ledger.balanceCents(AccountRef.program(AccountType.PAYPAL_FEES));
        String capture = "CAP-" + UUID.randomUUID();

        DonationReceipt first = donate(capture, 2_500, 100, "Maria", false);
        assertThat(first.replay()).isFalse();
        DonationReceipt again = donate(capture, 2_500, 100, "Maria", false);
        assertThat(again.replay()).isTrue();
        assertThat(again.donationId()).isEqualTo(first.donationId());

        assertThat(reserveBalance() - reserveBefore).as("the fund receives the gross gift").isEqualTo(2_500);
        assertThat(programCash() - cashBefore).as("cash is the gift less the PayPal fee").isEqualTo(2_400);
        assertThat(ledger.balanceCents(AccountRef.program(AccountType.PAYPAL_FEES)) - feesBefore).isEqualTo(100);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reserve.donation WHERE capture_id = :c").param("c", capture)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry WHERE source_key = :k")
                .param("k", "reserve-donation:" + capture).query(Integer.class).single()).isEqualTo(1);

        assertThatThrownBy(() -> donate(capture, 3_000, 100, "Maria", false))
                .hasFieldOrPropertyWithValue("errorCode.code", "idempotency-key-reused");
        assertThatThrownBy(() -> donate("CAP-zero", 0, 0, null, true))
                .hasFieldOrPropertyWithValue("errorCode.code", "donation-amount-invalid");
        assertThatThrownBy(() -> donate("CAP-fee", 100, 100, null, true))
                .hasFieldOrPropertyWithValue("errorCode.code", "donation-amount-invalid");
        assertThatThrownBy(() -> donate("CAP-huge", 10_000_001, 0, null, true))
                .hasFieldOrPropertyWithValue("errorCode.code", "donation-amount-invalid");
        assertThatThrownBy(() -> reserve.recordDonation("CAP-no-tx", 100, null, true))
                .as("the ledger entry must commit with the caller's transaction").isInstanceOf(IllegalTransactionStateException.class);

        assertFundMatchesLedger();
        assertJournalBalanced();
    }

    @Test
    @Order(3)
    void theSummaryIsPublicAndNamesOnlyDonorsWhoOptedIn() throws Exception {
        donate("CAP-maria-" + UUID.randomUUID(), 500, 0, "Maria", false);
        donate("CAP-anon-" + UUID.randomUUID(), 1_000, 0, "Secret Donor", true);
        donate("CAP-anon2-" + UUID.randomUUID(), 1_000, 0, "Another Secret", true);

        String body = mvc.perform(get("/api/v1/reserve/summary"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=30")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Secret");
        JsonNode summary = json.readTree(body);
        assertThat(summary.fieldNames()).toIterable().isSubsetOf("balanceCents", "totalDonatedCents", "donationCount",
                "totalPaidOutCents", "membersCovered", "openExposureCents", "coverageRatioBps", "supporters", "asOf");
        assertThat(summary.path("balanceCents").asLong()).isEqualTo(reserveBalance());
        assertThat(summary.path("supporters").toString()).contains("Maria").doesNotContain("Secret");

        // A donor who opts in through the intent is shown, cleaned of markup; an unknown custom id stays anonymous.
        JsonNode intent = json.readTree(mvc.perform(post("/api/v1/reserve/donations/intent").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "intent-" + UUID.randomUUID())
                        .content("{\"anonymous\":false,\"displayName\":\"Luz <i>Díaz</i>\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        tx.executeWithoutResult(s -> reserve.recordDonationForCustomId(intent.path("customId").asText(),
                "CAP-optin-" + UUID.randomUUID(), 2_000, 0));
        tx.executeWithoutResult(s -> reserve.recordDonationForCustomId("reserve-donation:" + UUID.randomUUID(),
                "CAP-unknown-" + UUID.randomUUID(), 500, 0));
        tx.executeWithoutResult(s -> reserve.recordDonationForCustomId("not-ours", "CAP-foreign-" + UUID.randomUUID(), 500, 0));

        ReserveSummary after = reserve.summary();
        assertThat(after.supporters()).contains("Luz Díaz", "Maria");
        assertThat(after.supporters()).doesNotContain("Secret Donor", "Another Secret");
        assertThat(after.supporters().toString()).doesNotContain("<").doesNotContain("@");
        assertFundMatchesLedger();
    }

    @Test
    @Order(4)
    void theDonationIntentIsPublicIdempotentAndPlain() throws Exception {
        String key = "intent-" + UUID.randomUUID();
        String body = "{\"anonymous\":true}";
        JsonNode first = json.readTree(mvc.perform(post("/api/v1/reserve/donations/intent").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customId").value(org.hamcrest.Matchers.matchesPattern("^reserve-donation:[0-9a-f-]{36}$")))
                .andReturn().getResponse().getContentAsString());
        JsonNode replay = json.readTree(mvc.perform(post("/api/v1/reserve/donations/intent").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(replay.path("customId").asText()).isEqualTo(first.path("customId").asText());
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("intentId", "customId");

        mvc.perform(post("/api/v1/reserve/donations/intent").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content("{\"anonymous\":false,\"displayName\":\"Tom\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency-key-reused"));
        mvc.perform(post("/api/v1/reserve/donations/intent").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reserve/donations/intent").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .content("{\"displayName\":\"" + "x".repeat(41) + "\"}"))
                .andExpect(status().isBadRequest());

        // Asking to be shown without a usable name is the same as staying anonymous.
        JsonNode nameless = json.readTree(mvc.perform(post("/api/v1/reserve/donations/intent")
                        .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .content("{\"anonymous\":false,\"displayName\":\"<>\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(jdbc.sql("SELECT anonymous FROM reserve.donation_intent WHERE id = :id")
                .param("id", UUID.fromString(nameless.path("intentId").asText())).query(Boolean.class).single()).isTrue();
    }

    @Test
    @Order(5)
    void aCoverNeedsTwoDifferentOfficersAndThenMovesMoneyFromTheFundToTheLoan() throws Exception {
        UUID member = UUID.randomUUID();
        LoanSummary loan = disburse(member, 600, 6, 15, DAY);
        settle();
        fund(20_000);
        long reserveBefore = reserveBalance();
        long receivableBefore = receivable(loan.loanId());
        assertThat(receivableBefore).isEqualTo(60_000);
        UUID raiser = UUID.randomUUID();
        UUID maker = UUID.randomUUID();
        UUID checker = UUID.randomUUID();

        ResultActions request = createCover(raiser, "OFFICER", member, loan.loanId(), 5_000, "cover-key-1");
        UUID coverId = created(request);
        request.andExpect(jsonPath("$.status").value("PENDING_APPROVAL")).andExpect(jsonPath("$.amountCents").value(5_000));
        assertThat(created(createCover(raiser, "OFFICER", member, loan.loanId(), 5_000, "cover-key-1")))
                .as("same key, same body: the stored request").isEqualTo(coverId);
        createCover(raiser, "OFFICER", member, loan.loanId(), 6_000, "cover-key-1")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency-key-reused"));
        createCover(raiser, "OFFICER", member, loan.loanId(), 4_000, "cover-key-2")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cover-request-open"));

        // Who may do what.
        createCover(UUID.randomUUID(), "CHECKER", member, loan.loanId(), 1_000, "cover-key-3").andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/desk/reserve/cover-requests").header("X-Seed-Member-Id", member.toString())
                        .header("Idempotency-Key", "cover-key-4").contentType(MediaType.APPLICATION_JSON)
                        .content(coverBody(member, loan.loanId(), 1_000)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/desk/reserve/cover-requests").header("Idempotency-Key", "cover-key-5")
                        .contentType(MediaType.APPLICATION_JSON).content(coverBody(member, loan.loanId(), 1_000)))
                .andExpect(status().isUnauthorized());
        decide(coverId, maker, "CHECKER", "APPROVE", "wrong-role").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("decider-role-required"));
        decide(coverId, UUID.randomUUID(), "MEMBER", "APPROVE", "member-role").andExpect(status().isForbidden());

        decide(coverId, maker, "OFFICER", "APPROVE", "maker-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MAKER")).andExpect(jsonPath("$.next").value("CHECKER"))
                .andExpect(jsonPath("$.coverStatus").value("PENDING_APPROVAL"));
        decide(coverId, maker, "OFFICER", "APPROVE", "maker-1").andExpect(status().isOk()); // idempotent replay
        decide(coverId, maker, "OFFICER,CHECKER", "APPROVE", "same-person").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("sod-violation"));
        assertThat(reserveBalance()).as("one signature moves nothing").isEqualTo(reserveBefore);
        assertThat(receivable(loan.loanId())).isEqualTo(receivableBefore);

        decide(coverId, checker, "CHECKER", "APPROVE", "checker-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("APPROVED")).andExpect(jsonPath("$.coverStatus").value("APPROVED"));
        decide(coverId, checker, "CHECKER", "APPROVE", "checker-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.coverStatus").value("APPROVED")); // replay posts nothing more
        decide(coverId, UUID.randomUUID(), "CHECKER", "APPROVE", "late").andExpect(status().isConflict());
        settle();

        assertThat(reserveBefore - reserveBalance()).isEqualTo(5_000);
        assertThat(receivableBefore - receivable(loan.loanId())).as("the fund pays the loan, not the member").isEqualTo(5_000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entry WHERE source_key = :k")
                .param("k", "reserve-cover:" + coverId).query(Integer.class).single()).isEqualTo(1);
        assertThat(published.stream(CoverApproved.class).toList()).singleElement().satisfies(e -> {
            assertThat(e.coverRequestId()).isEqualTo(coverId);
            assertThat(e.loanId()).isEqualTo(loan.loanId());
            assertThat(e.memberId()).isEqualTo(member);
            assertThat(e.amountCents()).isEqualTo(5_000);
            assertThat(e.approvalRef()).isEqualTo("reserve-cover:" + coverId);
        });
        assertThat(jdbc.sql("SELECT status FROM reserve.cover_request WHERE id = :id").param("id", coverId)
                .query(String.class).single()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT count(*) FROM governance.audit_entry WHERE subject_id = :id").param("id", coverId)
                .query(Integer.class).single()).as("request, two decisions").isGreaterThanOrEqualTo(3);
        assertFundMatchesLedger();
        assertJournalBalanced();
    }

    @Test
    @Order(6)
    void anOfficerCanRejectAndTheLoanCanBeAskedForAgain() throws Exception {
        UUID member = UUID.randomUUID();
        LoanSummary loan = disburse(member, 300, 6, 15, DAY);
        fund(5_000);
        long reserveBefore = reserveBalance();
        long receivableBefore = receivable(loan.loanId());
        UUID officer = UUID.randomUUID();

        UUID coverId = created(createCover(officer, "OFFICER", member, loan.loanId(), 4_000, "rej-1"));
        decide(coverId, UUID.randomUUID(), "OFFICER", "REJECT", "rej-maker").andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("REJECTED")).andExpect(jsonPath("$.coverStatus").value("REJECTED"));
        settle();
        assertThat(reserveBalance()).isEqualTo(reserveBefore);
        assertThat(receivable(loan.loanId())).isEqualTo(receivableBefore);
        assertThat(coverStatus(coverId)).isEqualTo("REJECTED");

        assertThat(created(createCover(officer, "OFFICER", member, loan.loanId(), 2_000, "rej-2"))).isNotEqualTo(coverId);
        assertJournalBalanced();
    }

    @Test
    @Order(7)
    void aCoverIsCappedPerRequestAndPerMemberPerYear() throws Exception {
        fund(150_000);
        UUID member = UUID.randomUUID();
        LoanSummary loanA = disburse(member, 600, 6, 15, DAY);
        LoanSummary loanB = disburse(member, 600, 6, 15, DAY);
        LoanSummary loanC = disburse(member, 600, 6, 15, DAY);
        UUID officer = UUID.randomUUID();

        createCover(officer, "OFFICER", member, loanC.loanId(), 50_001, "cap-big")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("cover-amount-exceeds-request-cap"));
        UUID a = created(createCover(officer, "OFFICER", member, loanA.loanId(), 50_000, "cap-a"));
        created(createCover(officer, "OFFICER", member, loanB.loanId(), 50_000, "cap-b"));
        createCover(officer, "OFFICER", member, loanC.loanId(), 1_000, "cap-c")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("cover-member-cap-exceeded"))
                .andExpect(jsonPath("$.details.remainingThisYearCents").value(0));

        // A rejected request frees its share of the yearly limit.
        decide(a, UUID.randomUUID(), "OFFICER", "REJECT", "cap-a-rej").andExpect(status().isOk());
        created(createCover(officer, "OFFICER", member, loanC.loanId(), 1_000, "cap-c2"));
        assertJournalBalanced();
    }

    @Test
    @Order(8)
    void aCoverMustBeForTheMembersOwnActiveLoanAndWithinWhatIsOwed() throws Exception {
        fund(20_000);
        UUID member = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        LoanSummary loan = disburse(member, 100, 4, 15, DAY);
        UUID officer = UUID.randomUUID();

        createCover(officer, "OFFICER", stranger, loan.loanId(), 1_000, "own-1")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("cover-loan-not-found"));
        createCover(officer, "OFFICER", member, UUID.randomUUID(), 1_000, "own-2").andExpect(status().isNotFound());
        createCover(officer, "OFFICER", member, loan.loanId(), 10_001, "own-3")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("cover-exceeds-outstanding"));
        mvc.perform(staff(post("/api/v1/desk/reserve/cover-requests"), officer, "OFFICER").header("Idempotency-Key", "own-4")
                        .contentType(MediaType.APPLICATION_JSON).content(coverBody(member, loan.loanId(), 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(staff(post("/api/v1/desk/reserve/cover-requests"), officer, "OFFICER").header("Idempotency-Key", "own-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + member + "\",\"loanId\":\"" + loan.loanId() + "\",\"amountCents\":100,\"reasonCode\":\"lower\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(9)
    void aDecisionMadeOutsideTheDeskStillSettlesTheCoverThroughTheEvent() throws Exception {
        UUID member = UUID.randomUUID();
        LoanSummary loan = disburse(member, 200, 4, 15, DAY);
        fund(10_000);
        long reserveBefore = reserveBalance();
        UUID coverId = created(createCover(UUID.randomUUID(), "OFFICER", member, loan.loanId(), 3_000, "evt-1"));
        UUID approvalId = jdbc.sql("SELECT approval_request_id FROM reserve.cover_request WHERE id = :id").param("id", coverId)
                .query(UUID.class).single();
        ApprovalView view = governance.request(approvalId).orElseThrow();

        governance.decide(new Decide(approvalId, UUID.randomUUID(), Set.of("OFFICER"), null, Decision.APPROVE, "OK_BY_PHONE", null,
                1_000, view.snapshotHash(), "direct-1"));
        governance.decide(new Decide(approvalId, UUID.randomUUID(), Set.of("CHECKER"), null, Decision.APPROVE, "OK_BY_PHONE", null,
                1_000, view.snapshotHash(), "direct-2"));
        settle();

        assertThat(coverStatus(coverId)).isEqualTo("APPROVED");
        assertThat(reserveBefore - reserveBalance()).isEqualTo(3_000);
        assertFundMatchesLedger();
        assertJournalBalanced();
    }

    @Test
    @Order(10)
    void coverRequestsAreListedNewestFirstWithAKeysetCursorForTheRightRoles() throws Exception {
        fund(10_000);
        UUID officer = UUID.randomUUID();
        UUID last = null;
        for (int i = 0; i < 3; i++) {
            UUID member = UUID.randomUUID();
            LoanSummary loan = disburse(member, 100, 4, 15, DAY);
            last = created(createCover(officer, "OFFICER", member, loan.loanId(), 1_000, "list-" + i));
        }
        JsonNode firstPage = json.readTree(mvc.perform(staff(get("/api/v1/desk/reserve/cover-requests"), UUID.randomUUID(), "CHECKER")
                        .param("limit", "2").param("status", "PENDING_APPROVAL"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(firstPage.path("rows")).hasSize(2);
        assertThat(firstPage.path("rows").get(0).path("id").asText()).isEqualTo(last.toString());
        UUID cursor = UUID.fromString(firstPage.path("nextBefore").asText());
        JsonNode secondPage = json.readTree(mvc.perform(staff(get("/api/v1/desk/reserve/cover-requests"), UUID.randomUUID(), "OFFICER")
                        .param("limit", "2").param("status", "PENDING_APPROVAL").param("before", cursor.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(secondPage.path("rows").size()).isGreaterThanOrEqualTo(1);
        assertThat(secondPage.path("rows").get(0).path("id").asText()).isNotEqualTo(last.toString());

        mvc.perform(get("/api/v1/desk/reserve/cover-requests").header("X-Seed-Member-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/desk/reserve/cover-requests")).andExpect(status().isUnauthorized());
        mvc.perform(staff(get("/api/v1/desk/reserve/cover-requests"), UUID.randomUUID(), "OFFICER").param("status", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(11)
    void theCoverageRatioIsTheBalanceOverWhatLoansStillOwe() throws Exception {
        UUID member = UUID.randomUUID();
        LoanSummary loan = disburse(member, 250, 5, 15, DAY);
        settle();
        long exposureOfLoan = jdbc.sql("SELECT outstanding_cents FROM reserve.loan_exposure WHERE loan_id = :id")
                .param("id", loan.loanId()).query(Long.class).single();
        assertThat(exposureOfLoan).isEqualTo(25_000);

        JsonNode summary = json.readTree(mvc.perform(get("/api/v1/reserve/summary")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        long expectedExposure = jdbc.sql("SELECT COALESCE(SUM(outstanding_cents), 0) FROM reserve.loan_exposure WHERE outstanding_cents > 0")
                .query(Long.class).single();
        assertThat(summary.path("openExposureCents").asLong()).isEqualTo(expectedExposure).isGreaterThanOrEqualTo(25_000);
        long balance = summary.path("balanceCents").asLong();
        assertThat(summary.path("coverageRatioBps").asLong()).isEqualTo(balance * 10_000 / expectedExposure);
        assertFundMatchesLedger();
    }

    // ---------------------------------------------------------------------------------------------------------

    /** A donation as PayPal reports it: a completed capture whose custom_id is the one the donor's intent made. */
    private static String donationCapture(String eventId, String captureId, String customId, long cents, long feeCents) {
        return """
                {"id":"%s","event_version":"1.0","create_time":"%s","event_type":"PAYMENT.CAPTURE.COMPLETED","resource":
                 {"id":"%s","status":"COMPLETED","custom_id":"%s","amount":{"value":"%s","currency_code":"USD"},
                  "seller_receivable_breakdown":{"paypal_fee":{"value":"%s","currency_code":"USD"}},"create_time":"%s"}}
                """.formatted(eventId, Instant.now(), captureId, customId, Money.ofCents(cents).toDecimalString(),
                Money.ofCents(feeCents).toDecimalString(), Instant.now());
    }

    private DonationReceipt donate(String capture, long amount, long fee, String name, boolean anonymous) {
        return tx.execute(s -> reserve.recordDonation(capture, amount, fee, name, anonymous));
    }

    private void fund(long cents) {
        donate("CAP-fund-" + UUID.randomUUID(), cents, 0, null, true);
    }

    private long reserveBalance() {
        return ledger.balanceCents(AccountRef.program(AccountType.LOSS_RESERVE));
    }

    private void assertFundMatchesLedger() {
        assertThat(reserve.summary().balanceCents()).as("fund counters and ledger agree").isEqualTo(reserveBalance());
    }

    private void assertJournalBalanced() {
        Long difference = jdbc.sql("SELECT COALESCE(SUM(CASE side WHEN 'DEBIT' THEN amount_cents ELSE -amount_cents END), 0) FROM ledger.journal_line")
                .query(Long.class).single();
        assertThat(difference).as("every debit has its credit").isZero();
    }

    private String coverStatus(UUID coverId) {
        return jdbc.sql("SELECT status FROM reserve.cover_request WHERE id = :id").param("id", coverId).query(String.class).single();
    }

    private void approveBoth(UUID coverId, UUID maker, UUID checker, String keyPrefix) throws Exception {
        decide(coverId, maker, "OFFICER", "APPROVE", keyPrefix + "-maker").andExpect(status().isOk());
        decide(coverId, checker, "CHECKER", "APPROVE", keyPrefix + "-checker").andExpect(status().isOk())
                .andExpect(jsonPath("$.coverStatus").value("APPROVED"));
    }

    private UUID created(ResultActions result) throws Exception {
        result.andExpect(status().isCreated());
        return UUID.fromString(json.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asText());
    }

    private ResultActions createCover(UUID staffId, String roles, UUID member, UUID loan, long amount, String key) throws Exception {
        return mvc.perform(staff(post("/api/v1/desk/reserve/cover-requests"), staffId, roles).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(coverBody(member, loan, amount)));
    }

    private static String coverBody(UUID member, UUID loan, long amount) {
        return "{\"memberId\":\"" + member + "\",\"loanId\":\"" + loan + "\",\"amountCents\":" + amount
                + ",\"reasonCode\":\"INCOME_DROP_VERIFIED\"}";
    }

    private ResultActions decide(UUID coverId, UUID staffId, String roles, String decision, String key) throws Exception {
        return mvc.perform(staff(post("/api/v1/desk/reserve/cover-requests/{id}/decision", coverId), staffId, roles)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\",\"reasonCode\":\"INCOME_DROP_VERIFIED\",\"evidenceViewedMs\":30000}"));
    }

    private static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder request, UUID staffId, String roles) {
        return request.header("X-Seed-Staff-Id", staffId.toString()).header("X-Seed-Staff-Roles", roles);
    }
}
