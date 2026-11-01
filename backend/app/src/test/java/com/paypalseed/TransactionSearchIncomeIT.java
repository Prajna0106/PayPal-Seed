package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentGrant;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchedTransaction;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import com.paypalseed.underwriting.application.usecases.IncomeConsentSync;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The fair check on income read from PayPal Transaction Search (ADR 0018): only with a consent, cached for a day,
 * stopped by a withdrawal in identity, and refused (retryably) when PayPal fails.
 */
@TestPropertySource(properties = {
        "seed.paypal.transaction-search.enabled=true",
        "seed.underwriting.income.source=TRANSACTION_SEARCH"})
class TransactionSearchIncomeIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    /** What the program account shows for 2026-08-11 .. 2026-11-09, netted by payments and cut into 13 weeks. */
    private static final List<Long> EXPECTED_WEEKS = List.of(
            19_410L, 0L, 0L, 3_000L, 5_680L, 0L, 0L, 50_000L, 0L, 0L, 0L, 0L, 7_000L);

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    IdentityFacade identity;
    @Autowired
    UnderwritingFacade underwriting;
    @Autowired
    IncomeConsentSync consentSync;

    private static SearchedTransaction line(String id, String code, String status, String at, long amount, long fee, String ref) {
        return new SearchedTransaction(id, code, status, Instant.parse(at), amount, fee, Optional.ofNullable(ref),
                Optional.of("PAYER-" + id), true, "Gig " + id);
    }

    @BeforeEach
    void seedPayPal() {
        clock.set(TODAY);
        paypal.transactions.clear();
        paypal.transactions.addAll(List.of(
                line("A", "T0006", "S", "2026-08-12T18:00:00Z", 20_000, -590, null),
                line("B", "T0006", "S", "2026-09-01T07:00:00Z", 3_000, 0, null),
                line("C", "T0006", "S", "2026-09-12T18:00:00Z", 10_000, -320, null),
                line("C-REFUND", "T1107", "S", "2026-09-13T18:00:00Z", -4_000, 0, "C"),
                line("D", "T0001", "S", "2026-10-02T18:00:00Z", 50_000, 0, null),
                line("FUNDING", "T0300", "S", "2026-10-05T18:00:00Z", 9_000, 0, null),
                line("PENDING", "T0006", "P", "2026-10-06T18:00:00Z", 7_000, 0, null),
                line("Z", "T0006", "S", "2026-11-09T18:00:00Z", 7_000, 0, null),
                line("TOO-OLD", "T0006", "S", "2026-07-01T18:00:00Z", 9_999, 0, null)));
    }

    private UUID linkedMember() {
        String payer = "PAYER-" + UUID.randomUUID();
        return identity.loginWithPayPal(new PayPalLogin(payer, "ana@example.test", "Ana", true, "en")).member().memberId();
    }

    /** Agrees to income read in both places, as the web app does: identity records, underwriting enforces. */
    private void consentEverywhere(UUID member) {
        identity.grantConsent(member, new ConsentGrant(ConsentType.INCOME_READ, null));
        underwriting.grantConsent(member);
    }

    private ResultActions assess(UUID member) throws Exception {
        // The monthly figures in the body are what a member could type; with the real source they must be ignored.
        String body = """
                {"purpose":"Commercial stand mixer","requestedAmountCents":120000,"requestedTermMonths":8,
                 "monthlyIncomes":[{"yearMonth":"2026-10","amountCents":999999}],
                 "statedMonthlyIncomeCents":160000,"statedMonthlyExpensesCents":90000,
                 "completedLoansOnTime":0,"hasActiveLoan":false}""";
        return mvc.perform(post("/api/v1/underwriting/assessments").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Seed-Member-Id", member.toString()));
    }

    private int searches() {
        return paypal.searches.size();
    }

    private int storedWeeks(UUID member) {
        return jdbc.sql("SELECT COUNT(*) FROM underwriting.income_week WHERE member_id = :m").param("m", member)
                .query(Integer.class).single();
    }

    private int assessments(UUID member) {
        return jdbc.sql("SELECT COUNT(*) FROM underwriting.fair_loan_check_assessment WHERE member_id = :m")
                .param("m", member).query(Integer.class).single();
    }

    @Test
    void withoutAConsentNothingIsFetchedAndTheCheckIsRefused() throws Exception {
        UUID member = linkedMember();

        assess(member).andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("consent-required")));

        assertThat(underwriting.incomeSummary(member, TODAY)).isEmpty();
        assertThat(searches()).isZero();
        assertThat(paypal.calls).noneMatch(call -> call.startsWith("searchTransactions"));
        assertThat(assessments(member)).isZero();
    }

    @Test
    void aConsentGrantedOnlyInIdentityDoesNotMakeSeedReadAnything() throws Exception {
        UUID member = linkedMember();
        identity.grantConsent(member, new ConsentGrant(ConsentType.INCOME_READ, null));

        assess(member).andExpect(status().isUnprocessableEntity());

        assertThat(searches()).isZero();
    }

    @Test
    void aConsentedMemberIsReadOnceThreeWindowsAndTheFigureComesFromPayPalNotTheRequest() throws Exception {
        UUID member = linkedMember();
        consentEverywhere(member);

        String response = assess(member).andExpect(status().isCreated())
                .andExpect(jsonPath("$.usedFallbackStatedIncome").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(searches()).isEqualTo(3); // 91 days in windows of at most 31
        IncomeSummary summary = underwriting.incomeSummary(member, TODAY).orElseThrow();
        assertThat(summary.weeklyInflowCents()).isEqualTo(EXPECTED_WEEKS);
        assertThat(summary.activeWeeks()).isEqualTo(5);
        JsonNode card = json.readTree(response);
        // (22_410 + 55_680 + 5_600) / 3; the 999_999 in the request body played no part.
        assertThat(card.get("averageMonthlyIncomeCents").asLong()).isBetween(27_896L, 27_897L);
        assertThat(jdbc.sql("SELECT income_refreshed_at IS NOT NULL AND income_anchor = :d FROM underwriting.income_consent WHERE member_id = :m")
                .param("d", TODAY).param("m", member).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aFreshSummaryIsCachedForADayThenReadAgain() throws Exception {
        UUID member = linkedMember();
        consentEverywhere(member);

        assess(member).andExpect(status().isCreated());
        assess(member).andExpect(status().isCreated());
        assertThat(searches()).isEqualTo(3);
        assertThat(assessments(member)).isEqualTo(2);

        // The next business day is exactly 24 hours later: still fresh. Two days later it is stale.
        clock.set(TODAY.plusDays(1));
        assess(member).andExpect(status().isCreated());
        assertThat(searches()).isEqualTo(3);

        clock.set(TODAY.plusDays(2));
        assess(member).andExpect(status().isCreated());
        assertThat(searches()).isEqualTo(6);
        // The window moved two days, so the 12 August payment is no longer in it.
        assertThat(underwriting.incomeSummary(member, TODAY.plusDays(2)).orElseThrow().activeWeeks()).isEqualTo(4);
    }

    @Test
    void theSummaryKeepsItsAlignmentForAFewDaysAfterTheReadAndThenShowsTheGap() throws Exception {
        UUID member = linkedMember();
        consentEverywhere(member);
        assess(member).andExpect(status().isCreated());

        assertThat(underwriting.incomeSummary(member, TODAY.plusDays(1)).orElseThrow().weeklyInflowCents())
                .isEqualTo(EXPECTED_WEEKS);
        // Past a week without a read the summary is cut for the day asked, so old data slides and shows as a gap.
        assertThat(underwriting.incomeSummary(member, TODAY.plusDays(8)).orElseThrow().weeklyInflowCents().get(12)).isZero();
    }

    @Test
    void withdrawingIncomeReadInIdentityStopsFurtherFetchesAndForgetsTheWeeks() throws Exception {
        UUID member = linkedMember();
        consentEverywhere(member);
        assess(member).andExpect(status().isCreated());
        assertThat(searches()).isEqualTo(3);
        assertThat(storedWeeks(member)).isEqualTo(13);

        assertThat(identity.revokeConsent(member, ConsentType.INCOME_READ)).isTrue();
        settle();

        assertThat(underwriting.getConsentStatus(member).consented()).isFalse();
        assertThat(underwriting.incomeSummary(member, TODAY)).isEmpty();
        assertThat(storedWeeks(member)).isZero();

        // Stale by two days, but with no consent nothing is read.
        clock.set(TODAY.plusDays(2));
        assess(member).andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("consent-required")));
        assertThat(searches()).isEqualTo(3);

        // Delivering the same withdrawal again changes nothing.
        consentSync.incomeReadWithdrawn(member);
        consentSync.incomeReadWithdrawn(member);
        assertThat(underwriting.getConsentStatus(member).consented()).isFalse();
    }

    @Test
    void withdrawingOtherConsentsLeavesIncomeReadAlone() {
        UUID member = linkedMember();
        consentEverywhere(member);
        identity.grantConsent(member, new ConsentGrant(ConsentType.RECORD_SHARING, null));

        identity.revokeConsent(member, ConsentType.RECORD_SHARING);
        settle();

        assertThat(underwriting.getConsentStatus(member).consented()).isTrue();
    }

    @Test
    void aPayPalFailureRefusesTheCheckWithARetryableProblemAndStoresNothing() throws Exception {
        UUID member = linkedMember();
        consentEverywhere(member);

        paypal.failNext("searchTransactions", FakePayPal.unavailable());
        assess(member).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("income-source-unavailable")))
                .andExpect(content().string(not(containsString("PayPal 503"))));
        assertThat(assessments(member)).isZero();
        assertThat(storedWeeks(member)).isZero();
        assertThat(underwriting.incomeSummary(member, TODAY).orElseThrow().activeWeeks()).isZero();

        paypal.failNext("searchTransactions", FakePayPal.refused("NOT_AUTHORIZED"));
        assess(member).andExpect(status().isServiceUnavailable());
        assertThat(assessments(member)).isZero();

        // The member can simply try again.
        assess(member).andExpect(status().isCreated());
        assertThat(assessments(member)).isEqualTo(1);
        assertThat(storedWeeks(member)).isEqualTo(13);
    }

    @Test
    void aFailedRefreshNeverFallsBackToOlderStoredWeeks() throws Exception {
        UUID member = linkedMember();
        consentEverywhere(member);
        assess(member).andExpect(status().isCreated());

        clock.set(TODAY.plusDays(2));
        paypal.failNext("searchTransactions", FakePayPal.unavailable());
        assess(member).andExpect(status().isServiceUnavailable());

        assertThat(assessments(member)).isEqualTo(1);
    }

    @Test
    void aMemberWhoNeverSignedInWithPayPalIsToldToLinkAnAccountAndNothingIsFetched() throws Exception {
        UUID member = UUID.randomUUID();
        underwriting.grantConsent(member);

        assess(member).andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("paypal-account-not-linked")));

        assertThat(searches()).isZero();
        assertThat(assessments(member)).isZero();
    }
}
