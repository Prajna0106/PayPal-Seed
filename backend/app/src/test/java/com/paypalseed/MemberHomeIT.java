package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The member Home page over real PostgreSQL: one call, independent sections, and only the signed-in member's data.
 * The per-member reads come from the production {@code FacadeMemberReads}, which asks the savings, lending and
 * hardship facades (ADR 0018, ADR 0020).
 */
@TestPropertySource(properties = "seed.hardship.agent-deadline=PT0S")
class MemberHomeIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    /** Lending fails for this member, to show one broken module never breaks the page. */
    private static final UUID BROKEN_LENDING = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @SpyBean
    LendingFacade lendingFacade;

    @Autowired
    ObjectMapper json;
    @Autowired
    IdentityFacade identity;
    @Autowired
    UnderwritingFacade underwriting;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase recordDeposit;
    @Autowired
    FairLoanCheckService fairCheck;
    @Autowired
    ReminderRepository contacts;

    @BeforeEach
    void today() {
        clock.set(TODAY);
    }

    private ResultActions home(UUID member) throws Exception {
        return mvc.perform(get("/api/v1/members/me/home").header("X-Seed-Member-Id", member.toString()));
    }

    private UUID signedIn(String locale) {
        return identity.loginWithPayPal(new PayPalLogin("PAYER-" + UUID.randomUUID(), "ana@example.test", "Ana", true, locale))
            .member().memberId();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Four monthly deposits of $75 toward a $1,200 goal: a saving streak of four. */
    private SavingsGoal savingFor(UUID member) {
        SavingsGoal goal = createGoal.execute(new CreateGoalUseCase.Command(member.toString(), "Commercial Stand Mixer Goal",
            Money.usd(1200.00), Money.usd(75.00), LocalDate.of(2027, 6, 1), "sub-" + member));
        for (int i = 1; i <= 4; i++) {
            recordDeposit.execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(75.00), "cap-" + member + "-" + i));
        }
        return goal;
    }

    private FairCheckCardView fairCheckFor(UUID member) {
        underwriting.grantConsent(member);
        return fairCheck.runFairLoanCheck(member, new RunFairCheckCommand(null, "Commercial stand mixer for home bakery",
            120_000L, 8, List.of(new IncomeMonthDto("2026-05", 165_000L), new IncomeMonthDto("2026-06", 172_000L),
                new IncomeMonthDto("2026-07", 118_000L), new IncomeMonthDto("2026-08", 165_000L),
                new IncomeMonthDto("2026-09", 170_000L), new IncomeMonthDto("2026-10", 170_000L)),
            160_000L, 90_000L, 0, false));
    }

    @Test
    void aBrandNewMemberGetsAPageWithEmptySectionsAndOneHonestGap() throws Exception {
        UUID member = UUID.randomUUID(); // never signed in with PayPal: no identity record

        JsonNode page = body(home(member).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.generatedOn").value("2026-11-10"))
            .andExpect(jsonPath("$.profile").doesNotExist())
            .andExpect(jsonPath("$.sectionErrors", hasSize(1)))
            .andExpect(jsonPath("$.sectionErrors[0].section").value("profile"))
            .andExpect(jsonPath("$.sectionErrors[0].code").value("no-data"))
            .andExpect(jsonPath("$.savings.currentStreakMonths").value(0))
            .andExpect(jsonPath("$.savings.totalSaved.cents").value(0))
            .andExpect(jsonPath("$.savings.totalSaved.currency").value("USD"))
            .andExpect(jsonPath("$.savings.goals", empty()))
            .andExpect(jsonPath("$.lending.eligibility.eligible").value(false))
            .andExpect(jsonPath("$.lending.activeLoan").doesNotExist())
            .andExpect(jsonPath("$.lending.openApplication").doesNotExist())
            .andExpect(jsonPath("$.underwriting.incomeConsent").value(false))
            .andExpect(jsonPath("$.underwriting.latestAssessment").doesNotExist())
            .andExpect(jsonPath("$.creditRecord.level").value("NEW"))
            .andExpect(jsonPath("$.hardship.offerWaiting").value(false)));

        assertThat(page.toString()).doesNotContain("riskLevel").doesNotContain("score").doesNotContain("points");
    }

    @Test
    void aMemberWithAGoalAndAStreakSeesThemInTheirLanguage() throws Exception {
        UUID member = signedIn("es-MX");
        SavingsGoal goal = savingFor(member);
        settle();

        home(member).andExpect(status().isOk())
            .andExpect(jsonPath("$.sectionErrors", empty()))
            .andExpect(jsonPath("$.profile.language").value("es"))
            .andExpect(jsonPath("$.profile.displayName").value("Ana"))
            .andExpect(jsonPath("$.profile.walletPreference").value("PAYPAL"))
            .andExpect(jsonPath("$.savings.currentStreakMonths", greaterThanOrEqualTo(4)))
            .andExpect(jsonPath("$.savings.totalSaved.cents").value(30_000))
            .andExpect(jsonPath("$.savings.monthlySaving.cents").value(7_500))
            .andExpect(jsonPath("$.savings.goals", hasSize(1)))
            .andExpect(jsonPath("$.savings.goals[0].goalId").value(goal.getId().toString()))
            .andExpect(jsonPath("$.savings.goals[0].name").value("Commercial Stand Mixer Goal"))
            .andExpect(jsonPath("$.savings.goals[0].target.cents").value(120_000))
            .andExpect(jsonPath("$.savings.goals[0].saved.cents").value(30_000))
            .andExpect(jsonPath("$.savings.goals[0].monthly.cents").value(7_500))
            .andExpect(jsonPath("$.savings.goals[0].targetDate").value("2027-06-01"))
            .andExpect(jsonPath("$.creditRecord.nextStep.hint", startsWith("Para llegar a")));
    }

    @Test
    void anEligibleMemberSeesWhatTheyCanBorrowAndWhereTheirFairCheckStands() throws Exception {
        UUID member = signedIn("en");
        savingFor(member);
        FairCheckCardView card = fairCheckFor(member);
        settle();

        home(member).andExpect(status().isOk())
            .andExpect(jsonPath("$.lending.eligibility.eligible").value(true))
            .andExpect(jsonPath("$.lending.eligibility.maxMonthlyInstalment.cents").value(15_000))
            .andExpect(jsonPath("$.lending.eligibility.reasons").isArray())
            .andExpect(jsonPath("$.lending.activeLoan").doesNotExist())
            .andExpect(jsonPath("$.underwriting.incomeConsent").value(true))
            .andExpect(jsonPath("$.underwriting.latestAssessment.assessmentId").value(card.assessmentId().toString()))
            .andExpect(jsonPath("$.underwriting.latestAssessment.decision").value("PENDING"))
            .andExpect(jsonPath("$.underwriting.latestAssessment.memberDisputed").value(false));

        fairCheck.recordOfficerDecision(card.assessmentId(), UUID.randomUUID(), OfficerDecisionType.APPROVED, null, null, "Verified.");
        home(member).andExpect(jsonPath("$.underwriting.latestAssessment.decision").value("APPROVED"));
    }

    @Test
    void aMemberWithAnActiveLoanAndAutopaySeesTheNextPaymentAndTheMandate() throws Exception {
        UUID member = signedIn("en");
        LoanSummary loan = disburse(member, 1_200, 8, 20, TODAY);
        String subscription = startAutopay(loan);
        paypal.approve(subscription);
        FakePayPal.Sub sub = paypal.subscriptions.get(subscription);
        webhook(PayPalWebhooks.subscription(PayPalWebhooks.newEventId(), "BILLING.SUBSCRIPTION.ACTIVATED", subscription,
            "ACTIVE", sub.quantity, sub.nextBilling));
        settle();
        var firstDue = lending.currentSchedule(loan.loanId()).instalments().get(0);

        home(member).andExpect(status().isOk())
            .andExpect(jsonPath("$.sectionErrors", empty()))
            .andExpect(jsonPath("$.lending.activeLoan.loanId").value(loan.loanId().toString()))
            .andExpect(jsonPath("$.lending.activeLoan.status").value("ACTIVE"))
            .andExpect(jsonPath("$.lending.activeLoan.outstanding.cents").value(120_000))
            .andExpect(jsonPath("$.lending.activeLoan.nextPayment.seq").value(1))
            .andExpect(jsonPath("$.lending.activeLoan.nextPayment.dueDate").value(firstDue.dueDate().toString()))
            .andExpect(jsonPath("$.lending.activeLoan.nextPayment.amount.cents").value((int) firstDue.amountCents()))
            .andExpect(jsonPath("$.lending.activeLoan.nextPayment.remaining.cents").value((int) firstDue.remainingCents()))
            .andExpect(jsonPath("$.lending.activeLoan.autopay.status").value("ACTIVE"))
            .andExpect(jsonPath("$.lending.activeLoan.autopay.nextBillingDate").exists())
            .andExpect(jsonPath("$.lending.eligibility.eligible").value(false));

        // Nobody else sees this loan.
        home(signedIn("en")).andExpect(status().isOk()).andExpect(jsonPath("$.lending.activeLoan").doesNotExist());
    }

    @Test
    void aMemberWithAHardshipOfferSeesThatOptionsAreWaitingAndNoRisk() throws Exception {
        UUID member = UUID.randomUUID();
        LoanSummary loan = disburse(member, 1_200, 8, 20, TODAY);
        contacts.upsertContact(member, "+15550007001", "en", "America/Los_Angeles");
        LocalDate due = lending.currentSchedule(loan.loanId()).instalments().get(0).dueDate();
        LocalDate scanDate = due.minusDays(8);
        clock.set(scanDate);
        List<String> weeks = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            weeks.add("{\"weekStart\":\"" + scanDate.minusDays(91 - 7L * i) + "\",\"inflowCents\":" + (i < 9 ? 20_000 : 9_600) + "}");
        }
        mvc.perform(put("/internal/underwriting/members/{id}/income", member).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", SERVICE_TOKEN).content("{\"consent\":true,\"weeks\":[" + String.join(",", weeks) + "]}"))
            .andExpect(status().isOk());
        mvc.perform(post("/internal/jobs/hardship/scan").header("Authorization", SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"" + scanDate + "\",\"shardGroup\":0,\"shardCount\":1,\"limit\":500}"))
            .andExpect(status().isOk());
        settle();
        mvc.perform(post("/internal/jobs/hardship/sweep").header("Authorization", SERVICE_TOKEN)).andExpect(status().isOk());
        settle();
        UUID caseId = jdbc.sql("SELECT id FROM hardship.hardship_case WHERE loan_id = ?").param(loan.loanId())
            .query(UUID.class).single();

        JsonNode page = body(home(member).andExpect(status().isOk())
            .andExpect(jsonPath("$.hardship.offerWaiting").value(true))
            .andExpect(jsonPath("$.hardship.caseId").value(caseId.toString()))
            .andExpect(jsonPath("$.hardship.loanId").value(loan.loanId().toString()))
            .andExpect(jsonPath("$.hardship.status").value("OUTREACH_SENT")));

        // Rule 4: members see options, never their risk level, points or signals.
        assertThat(page.toString()).doesNotContain("LIKELY_MISS").doesNotContain("riskLevel").doesNotContain("points")
            .doesNotContain("signal").doesNotContain("score");
    }

    @Test
    void oneBrokenModuleDoesNotBreakThePageAndLeaksNothing() throws Exception {
        doThrow(new IllegalStateException("jdbc:postgresql://secret-host/seed password=hunter2"))
            .when(lendingFacade).eligibilityOf(BROKEN_LENDING);
        home(BROKEN_LENDING).andExpect(status().isOk())
            .andExpect(jsonPath("$.lending").doesNotExist())
            .andExpect(jsonPath("$.sectionErrors[?(@.section=='lending')].code").value("unavailable"))
            .andExpect(jsonPath("$.savings.currentStreakMonths").value(0))
            .andExpect(jsonPath("$.creditRecord.level").value("NEW"))
            .andExpect(jsonPath("$.underwriting.incomeConsent").value(false))
            .andExpect(content().string(not(containsString("hunter2"))))
            .andExpect(content().string(not(containsString("secret-host"))))
            .andExpect(content().string(not(containsString("IllegalState"))));
    }

    @Test
    void theRouteIsAlwaysMeSoAskingForSomeoneElseGivesYouYourOwnPage() throws Exception {
        UUID ana = signedIn("en");
        UUID other = signedIn("es");
        savingFor(other);
        settle();

        // A member id in the query string, or in the path, changes nothing.
        mvc.perform(get("/api/v1/members/me/home").param("memberId", other.toString())
                .header("X-Seed-Member-Id", ana.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile.language").value("en"))
            .andExpect(jsonPath("$.savings.totalSaved.cents").value(0))
            .andExpect(jsonPath("$.savings.goals", empty()));
        mvc.perform(get("/api/v1/members/{id}/home", other).header("X-Seed-Member-Id", ana.toString()))
            .andExpect(status().is4xxClientError());
        // Other member's page, read by its owner, still has their data.
        home(other).andExpect(jsonPath("$.savings.totalSaved.cents").value(30_000));
    }

    @Test
    void staffAndTheServiceAreNotMembersAndAnonymousCallersAreNotSignedIn() throws Exception {
        mvc.perform(get("/api/v1/members/me/home").header("X-Seed-Staff-Id", UUID.randomUUID().toString())
                .header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/members/me/home").header("X-Seed-Staff-Id", UUID.randomUUID().toString())
                .header("X-Seed-Staff-Roles", "ADMIN,CHECKER"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/members/me/home")).andExpect(status().isUnauthorized());
        // The service credential belongs to /internal only (ADR 0016).
        mvc.perform(get("/api/v1/members/me/home").header("Authorization", SERVICE_TOKEN)).andExpect(status().isUnauthorized());
    }

    private String startAutopay(LoanSummary loan) throws Exception {
        String response = mvc.perform(post("/api/v1/loans/{id}/autopay", loan.loanId())
                .header("X-Seed-Member-Id", loan.memberId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"returnUrl\":\"https://seed.test/return\",\"cancelUrl\":\"https://seed.test/cancel\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("subscriptionId").asText();
    }
}
