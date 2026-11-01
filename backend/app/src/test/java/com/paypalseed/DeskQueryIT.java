package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.deskquery.api.DeskQueryFacade;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.PortfolioSummary;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection;
import com.paypalseed.deskquery.application.usecases.ApplicationProjector;
import com.paypalseed.deskquery.application.usecases.AssessmentProjector;
import com.paypalseed.deskquery.application.usecases.LoanProjector;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The officer desk's read models end to end: real savings, fair check, application, approval, disbursement, repayment and
 * hardship flows publish their events, the projections follow, and the five desk endpoints answer from them.
 *
 * <p>The application queue and the decisions queue fill from the {@code LoanApplicationChanged} and
 * {@code AssessmentChanged} events lending and underwriting publish (ADR 0021), like every other projection. Only the
 * synthetic rows that exist to create ties for the paging tests are applied directly.
 */
@TestPropertySource(properties = "seed.hardship.agent-deadline=PT0S")
class DeskQueryIT extends IntegrationTest {

    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10);

    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase recordDeposit;
    @Autowired
    FairLoanCheckService fairCheck;
    @Autowired
    MicroloanApplicationService microloans;
    @Autowired
    ApplicationProjector applications;
    @Autowired
    AssessmentProjector assessments;
    @Autowired
    LoanProjector loanProjector;
    @Autowired
    DeskQueryFacade desk;
    @Autowired
    IncompleteEventPublications incomplete;
    @Autowired
    ReminderRepository contacts;
    @Autowired
    ObjectMapper json;
    @Autowired
    IdentityFacade identity;

    private final UUID officerOne = UUID.randomUUID();
    private final UUID officerTwo = UUID.randomUUID();

    // ---------------------------------------------------------------------------------------------------------------
    // The whole journey
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theDeskFollowsSavingsApplicationApprovalRepaymentAndHardship() throws Exception {
        // The loan is paid out through PayPal, so Ana signs in with PayPal (that is where the payout goes) and the
        // simulator answers the payout at once.
        UUID ana = identity.loginWithPayPal(new PayPalLogin("PAYER-ANA-" + UUID.randomUUID(), "ana@example.test", "Ana", true, "en"))
                .member().memberId();
        paypal.payoutOutcome = FakePayPal.PayoutOutcome.INSTANT_SUCCESS;

        // F1: Ana saves $75 a month; four deposits arrive in the current calendar month.
        SavingsGoal goal = createGoal.execute(new CreateGoalUseCase.Command(ana.toString(),
                "Commercial stand mixer", Money.usd(1200.00), Money.usd(75.00), LocalDate.of(2027, 6, 1), "sub-ana-" + ana));
        for (int i = 1; i <= 4; i++) {
            recordDeposit.execute(new RecordDepositUseCase.Command(goal.getId(), Money.usd(75.00), "cap-ana-" + i));
        }

        // F3 and F2: consent, a fair check, the application; the desk queue shows it waiting for a first officer.
        fairCheck.grantConsent(ana);
        FairCheckCardView firstCheck = fairCheck.runFairLoanCheck(ana, new RunFairCheckCommand(null, "Commercial stand mixer", 120_000L, 8,
                List.of(new IncomeMonthDto("2026-05", 165_000L), new IncomeMonthDto("2026-06", 172_000L),
                        new IncomeMonthDto("2026-07", 118_000L), new IncomeMonthDto("2026-08", 165_000L),
                        new IncomeMonthDto("2026-09", 170_000L), new IncomeMonthDto("2026-10", 170_000L)),
                160_000L, 90_000L, 0, false));
        LoanApplicationView application = microloans.submitApplication(ana, new SubmitApplicationCommand("Commercial stand mixer", 120_000L, 8,
                15, WalletType.PAYPAL, true, OptionalLong.empty()));
        settle();

        JsonNode queue = staffGet("/api/v1/desk/applications", "OFFICER");
        JsonNode row = rowOf(queue, "applicationId", application.id());
        assertThat(row.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(row.path("requestedAmountCents").asLong()).isEqualTo(120_000);
        assertThat(row.path("termMonths").asInt()).isEqualTo(8);
        // Four deposits, one calendar month: the streak counts consecutive months with a deposit, not deposits.
        assertThat(row.path("savingStreakMonths").asInt()).isEqualTo(1);
        FairCheckCardView linkedCard = fairCheck.listMemberAssessments(ana).stream()
                .filter(c -> application.id().equals(c.applicationId())).findFirst().orElseThrow();
        assertThat(row.path("assessmentOutcome").asText()).isEqualTo(linkedCard.recommended() ? "RECOMMENDED" : "NOT_RECOMMENDED");
        assertThat(row.path("memberRef").asText()).isEqualTo(ana.toString());
        assertThat(row.has("assignedOfficerRef")).isFalse();
        assertNoPersonalData(queue);
        assertThat(waiting(staffGet("/api/v1/desk/workload", "OFFICER"), "LOAN_APPLICATION", "OFFICER")).isEqualTo(1);

        // The member disputes one assessment and an officer decides it; the decisions queue follows.
        fairCheck.disputeAssessment(firstCheck.assessmentId(), ana, "July was a two-week vacation closure.");
        fairCheck.recordOfficerDecision(firstCheck.assessmentId(), officerOne, OfficerDecisionType.APPROVED, null, null,
                "Verified 4/4 savings deposits and steady revenue.");
        settle();
        JsonNode decided = rowOf(staffGet("/api/v1/desk/assessments", "CHECKER"), "assessmentId", firstCheck.assessmentId());
        assertThat(decided.path("status").asText()).isEqualTo("APPROVED");
        assertThat(decided.path("disputed").asBoolean()).isTrue();
        assertThat(decided.path("recommendedAmountCents").asLong()).isEqualTo(firstCheck.suggestedLoanCents());
        assertThat(decided.path("applicationId").isMissingNode()).isTrue();
        JsonNode linked = rowOf(staffGet("/api/v1/desk/assessments", "CHECKER"), "applicationId", application.id());
        assertThat(linked.path("status").asText()).isEqualTo("PENDING");
        assertThat(linked.path("memberRef").asText()).isEqualTo(ana.toString());
        assertThat(rows(staffGet("/api/v1/desk/assessments", "OFFICER", "disputed", "true"))).hasSize(1);
        assertThat(rows(staffGet("/api/v1/desk/assessments", "OFFICER", "status", "PENDING"))).hasSize(1);

        // Two officers approve; the queue moves from OFFICER to CHECKER to nobody, and shows who acted last.
        microloans.approveApplication(application.id(), officerOne);
        settle();
        assertThat(waiting(staffGet("/api/v1/desk/workload", "OFFICER"), "LOAN_APPLICATION", "OFFICER")).isZero();
        assertThat(waiting(staffGet("/api/v1/desk/workload", "OFFICER"), "LOAN_APPLICATION", "CHECKER")).isEqualTo(1);
        row = rowOf(staffGet("/api/v1/desk/applications", "OFFICER"), "applicationId", application.id());
        assertThat(row.path("status").asText()).isEqualTo("PENDING_SECOND_APPROVAL");
        assertThat(row.path("assignedOfficerRef").asText()).isNotBlank().doesNotContain(officerOne.toString());
        microloans.approveApplication(application.id(), officerTwo);
        settle();
        assertThat(waiting(staffGet("/api/v1/desk/workload", "OFFICER"), "LOAN_APPLICATION", "CHECKER")).isZero();
        LoanApplicationView disbursed = microloans.disburseApplication(application.id());
        assertThat(disbursed.status()).as("the simulated payout succeeded at once").isEqualTo("DISBURSED");
        settle();
        UUID anaLoan = disbursed.loanId();
        row = rowOf(staffGet("/api/v1/desk/applications", "OFFICER"), "applicationId", application.id());
        assertThat(row.path("status").asText()).isEqualTo("DISBURSED");
        assertThat(row.path("slaState").asText()).isEqualTo("NO_SLA");
        assertThat(rows(staffGet("/api/v1/desk/applications", "OFFICER", "status", "DISBURSED"))).hasSize(1);

        // The disbursed loan is on the portfolio grid with its schedule facts.
        JsonNode loanRow = rowOf(staffGet("/api/v1/desk/portfolio/loans", "OFFICER"), "loanId", anaLoan);
        assertThat(loanRow.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(loanRow.path("principalCents").asLong()).isEqualTo(120_000);
        assertThat(loanRow.path("outstandingCents").asLong()).isEqualTo(120_000);
        assertThat(loanRow.path("agingBucket").asText()).isEqualTo("CURRENT");
        assertThat(loanRow.path("autopay").asBoolean()).isFalse();
        assertThat(loanRow.path("hardship").asBoolean()).isFalse();
        assertThat(loanRow.path("nextDueDate").asText()).isEqualTo(lending.currentSchedule(anaLoan).instalments().get(0).dueDate().toString());
        assertThat(loanRow.path("nextDueCents").asLong()).isEqualTo(15_000);

        // A portfolio with ties on the default sort key: 7 loans on one date with one principal, more on others.
        List<UUID> loans = new ArrayList<>(List.of(anaLoan));
        for (int i = 0; i < 7; i++) {
            loans.add(disburse(UUID.randomUUID(), 600, 6, 25, DISBURSED).loanId());
        }
        for (int i = 0; i < 4; i++) {
            loans.add(disburse(UUID.randomUUID(), 900, 9, 27, DISBURSED.plusDays(2)).loanId());
        }
        settle();
        assertKeysetPaging(loans.size());

        // Repayment: Ana pays off her first loan with nothing missed.
        clock.set(DISBURSED.plusDays(3));
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-DESK-ANA", anaLoan, 120_000));
        settle();
        loanRow = rowOf(staffGet("/api/v1/desk/portfolio/loans", "OFFICER"), "loanId", anaLoan);
        assertThat(loanRow.path("status").asText()).isEqualTo("REPAID");
        assertThat(loanRow.path("outstandingCents").asLong()).isZero();
        assertThat(loanRow.has("nextDueDate")).isFalse();

        // Hardship: Bo's income drops, the safety net opens a case, Bo chooses a pause, two officers approve it.
        HardshipMember bo = openHardshipCase("+15550004001");
        settle();
        assertThat(rowOf(staffGet("/api/v1/desk/portfolio/loans", "OFFICER"), "loanId", bo.loanId()).path("hardship").asBoolean()).isTrue();
        assertThat(rows(staffGet("/api/v1/desk/portfolio/loans", "OFFICER", "hardship", "true"))).hasSize(1);
        assertThat(staffGet("/api/v1/desk/portfolio/summary", "OFFICER").path("openHardshipCases").asLong()).isEqualTo(1);

        JsonNode offer = json.readTree(asMember(get("/api/v1/loans/{id}/hardship", bo.loanId()), bo.memberId())).path("offer");
        mvc.perform(asMemberRequest(post("/api/v1/hardship/cases/{id}/choice", bo.caseId()), bo.memberId())
                        .header("Idempotency-Key", "desk-q-choice").contentType(MediaType.APPLICATION_JSON).content(choice(offer, "B")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        settle();
        JsonNode workload = staffGet("/api/v1/desk/workload", "OFFICER");
        assertThat(waiting(workload, "HARDSHIP_DECISION", "OFFICER")).isEqualTo(1);
        assertThat(cellBuckets(workload, "HARDSHIP_DECISION")).doesNotContain("NO_SLA");

        decide(bo.caseId(), officerOne, "OFFICER", "desk-q-maker").andExpect(status().isOk());
        settle();
        workload = staffGet("/api/v1/desk/workload", "OFFICER");
        assertThat(waiting(workload, "HARDSHIP_DECISION", "OFFICER")).isZero();
        assertThat(waiting(workload, "HARDSHIP_DECISION", "CHECKER")).isEqualTo(1);
        decide(bo.caseId(), officerTwo, "CHECKER", "desk-q-checker").andExpect(status().isOk());
        awaitCaseStatus(bo.caseId(), "APPLIED");
        settle();
        workload = staffGet("/api/v1/desk/workload", "OFFICER");
        assertThat(waiting(workload, "HARDSHIP_DECISION", "CHECKER")).isZero();
        assertThat(lending.currentSchedule(bo.loanId()).version()).isEqualTo(2);

        JsonNode boRow = rowOf(staffGet("/api/v1/desk/portfolio/loans", "OFFICER"), "loanId", bo.loanId());
        assertThat(boRow.path("hardship").asBoolean()).isFalse();
        assertThat(boRow.path("nextDueDate").asText()).isEqualTo(lending.currentSchedule(bo.loanId()).instalments().get(0).dueDate().toString());

        // More queue rows with ties, then the full set of figures against what the source modules say.
        clock.set(LocalDate.of(2027, 1, 10));
        feedTiedQueueRows();
        assertQueuePaging();
        assertGridRowsAgreeWithLending(loans, bo.loanId());
        PortfolioSummary summary = desk.portfolioSummary();
        assertSummary(loans.size() + 1, anaLoan, bo.loanId());

        // The summary over HTTP equals the facade's.
        JsonNode http = staffGet("/api/v1/desk/portfolio/summary", "ADMIN");
        assertThat(http.path("loans").asLong()).isEqualTo(summary.loans());
        assertThat(http.path("impact").path("membersLoanedWithoutCreditScore").asLong()).isEqualTo(summary.impact().membersWithLoan());
        assertThat(http.path("asOf").asText()).isNotBlank();

        assertOutOfOrderAndRebuild(anaLoan);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Pagination
    // ---------------------------------------------------------------------------------------------------------------

    /** Walks the loans grid page by page in several orders and checks nothing is skipped, repeated or out of order. */
    private void assertKeysetPaging(int expectedLoans) throws Exception {
        Set<String> source = new HashSet<>(jdbc.sql("SELECT id::text FROM lending.loan").query(String.class).list());
        assertThat(source).hasSize(expectedLoans);

        JsonNode first = staffGet("/api/v1/desk/portfolio/loans", "OFFICER", "limit", "3");
        assertThat(first.path("rows")).hasSize(3);
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(first.path("total").path("value").asLong()).isEqualTo(expectedLoans);
        assertThat(first.path("total").path("exact").asBoolean()).isTrue();

        for (String sort : List.of("disbursedOn:desc", "disbursedOn", "principalCents:desc", "principalCents", "outstandingCents,daysLate:desc",
                "agingBucket:desc,disbursedOn", "nextDueDate")) {
            List<JsonNode> all = walk("/api/v1/desk/portfolio/loans", sort, 3);
            assertThat(all.stream().map(r -> r.path("loanId").asText()).toList()).as(sort).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(source);
            assertThat(all.size() / 3).as(sort).isGreaterThanOrEqualTo(3);
            assertOrdered(all, sort);
            // The same order in one big page and in small ones.
            JsonNode big = staffGet("/api/v1/desk/portfolio/loans", "OFFICER", "sort", sort, "limit", "200");
            assertThat(ids(rows(big))).as(sort).isEqualTo(ids(all));
            assertThat(big.has("nextCursor")).isFalse();
        }

        // Ties on the sort key: seven loans share one date and one principal, yet every page boundary is clean.
        List<JsonNode> byPrincipal = walk("/api/v1/desk/portfolio/loans", "principalCents", 2);
        assertThat(byPrincipal.stream().filter(r -> r.path("principalCents").asLong() == 60_000)).hasSize(7);
        assertThat(ids(byPrincipal)).doesNotHaveDuplicates();

        // A cursor belongs to the query that produced it.
        String cursor = first.path("nextCursor").asText();
        mvc.perform(staffRequest(get("/api/v1/desk/portfolio/loans"), "OFFICER").param("limit", "3").param("sort", "principalCents").param("cursor", cursor))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("desk-query-invalid-cursor"));
        mvc.perform(staffRequest(get("/api/v1/desk/portfolio/loans"), "OFFICER").param("limit", "3").param("autopay", "true").param("cursor", cursor))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("desk-query-invalid-cursor"));
        mvc.perform(staffRequest(get("/api/v1/desk/applications"), "OFFICER").param("limit", "3").param("cursor", cursor))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("desk-query-invalid-cursor"));
        mvc.perform(staffRequest(get("/api/v1/desk/portfolio/loans"), "OFFICER").param("limit", "3")
                        .param("cursor", cursor.substring(0, cursor.length() - 3) + "AAA"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("desk-query-invalid-cursor"));
    }

    /** Eight applications and eight assessments that tie on every time column, split across several pages. */
    private void feedTiedQueueRows() {
        Instant tied = clock.now().minus(Duration.ofHours(3));
        for (int i = 0; i < 8; i++) {
            applications.apply(new ApplicationProjector.Snapshot(UUID.randomUUID(), UUID.randomUUID(), i % 4 == 0 ? "PENDING_SECOND_APPROVAL" : "SUBMITTED",
                    50_000 + 10_000L * (i % 3), 6, tied, null, tied));
        }
        // One long overdue, one submitted a minute ago.
        applications.apply(new ApplicationProjector.Snapshot(UUID.randomUUID(), UUID.randomUUID(), "SUBMITTED", 70_000, 6,
                clock.now().minus(Duration.ofHours(40)), null, clock.now().minus(Duration.ofHours(40))));
        applications.apply(new ApplicationProjector.Snapshot(UUID.randomUUID(), UUID.randomUUID(), "SUBMITTED", 70_000, 6,
                clock.now().minus(Duration.ofMinutes(1)), null, clock.now().minus(Duration.ofMinutes(1))));
        for (int i = 0; i < 8; i++) {
            assessments.apply(new AssessmentProjector.Snapshot(UUID.randomUUID(), UUID.randomUUID(), null, i % 3 != 0, 80_000L + 5_000L * (i % 2),
                    i % 4 == 0 ? "DECLINED" : "PENDING", i % 2 == 0, tied, tied));
        }
    }

    private void assertQueuePaging() throws Exception {
        for (String sort : List.of("slaDueAt", "submittedAt:desc", "requestedAmountCents:desc", "status,termMonths:desc")) {
            List<JsonNode> all = walk("/api/v1/desk/applications", sort, 4);
            assertThat(all).as(sort).hasSizeGreaterThanOrEqualTo(11);
            assertThat(ids(all)).as(sort).doesNotHaveDuplicates();
            assertThat(ids(all)).as(sort).isEqualTo(ids(rows(staffGet("/api/v1/desk/applications", "OFFICER", "sort", sort, "limit", "200"))));
        }
        for (String sort : List.of("createdAt:desc", "recommendedAmountCents", "status,createdAt:desc")) {
            List<JsonNode> all = walk("/api/v1/desk/assessments", sort, 3);
            assertThat(all).as(sort).hasSizeGreaterThanOrEqualTo(10);
            assertThat(ids(all)).as(sort).doesNotHaveDuplicates();
            assertThat(ids(all)).as(sort).isEqualTo(ids(rows(staffGet("/api/v1/desk/assessments", "OFFICER", "sort", sort, "limit", "200"))));
        }

        // SLA breach: the 40-hour-old application and every tied one (3 hours old, 24-hour SLA) is not yet breached.
        List<JsonNode> breached = rows(staffGet("/api/v1/desk/applications", "OFFICER", "slaBreached", "true", "limit", "200"));
        assertThat(breached).hasSize(1).allMatch(r -> r.path("slaState").asText().equals("OVERDUE") && r.path("ageDays").asLong() == 1);
        List<JsonNode> open = rows(staffGet("/api/v1/desk/applications", "OFFICER", "status", "SUBMITTED,PENDING_SECOND_APPROVAL", "limit", "200"));
        assertThat(open).allMatch(r -> !r.path("status").asText().equals("DISBURSED"));
        JsonNode workload = staffGet("/api/v1/desk/workload", "OFFICER");
        assertThat(waiting(workload, "LOAN_APPLICATION", "OFFICER")).isEqualTo(8);   // 6 tied SUBMITTED + the old one + the new one
        assertThat(waiting(workload, "LOAN_APPLICATION", "CHECKER")).isEqualTo(2);    // the tied PENDING_SECOND_APPROVAL ones
        assertThat(cellWaiting(workload, "LOAN_APPLICATION", "OFFICER", "OVERDUE")).isEqualTo(1);
        assertThat(workload.path("byRole")).anySatisfy(r -> {
            assertThat(r.path("role").asText()).isEqualTo("OFFICER");
            assertThat(r.path("overdue").asLong()).isEqualTo(1);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Figures
    // ---------------------------------------------------------------------------------------------------------------

    /** Every row on the grid carries what lending itself says about that loan. */
    private void assertGridRowsAgreeWithLending(List<UUID> loans, UUID boLoan) throws Exception {
        List<UUID> all = new ArrayList<>(loans);
        all.add(boLoan);
        List<JsonNode> rows = walk("/api/v1/desk/portfolio/loans", "disbursedOn:desc", 5);
        assertThat(rows).hasSize(all.size());
        for (JsonNode r : rows) {
            var loan = queries.loan(UUID.fromString(r.path("loanId").asText()));
            assertThat(r.path("status").asText()).isEqualTo(loan.status().name());
            assertThat(r.path("outstandingCents").asLong()).isEqualTo(loan.outstandingCents());
        }
    }

    private void assertSummary(int loanCount, UUID anaLoan, UUID boLoan) throws Exception {
        PortfolioSummary s = desk.portfolioSummary();
        List<UUID> ids = jdbc.sql("SELECT id FROM lending.loan").query(UUID.class).list();
        assertThat(ids).hasSize(loanCount);

        long outstanding = ids.stream().mapToLong(id -> queries.loan(id).outstandingCents()).sum();
        assertThat(s.loans()).isEqualTo(loanCount);
        assertThat(s.outstandingCents()).isEqualTo(outstanding);
        assertThat(s.byStatus()).extracting(t -> t.status() + ":" + t.loans()).containsExactly("ACTIVE:" + (loanCount - 1), "REPAID:1");
        assertThat(s.byAgingBucket()).extracting(b -> b.bucket()).containsExactly("CURRENT", "DPD_1_7", "DPD_8_30", "DPD_31_60", "DPD_60_PLUS");
        assertThat(s.byAgingBucket().get(0).loans()).isEqualTo(loanCount - 1);
        assertThat(s.byAgingBucket().stream().skip(1).mapToLong(b -> b.loans()).sum()).isZero();

        // Instalments due by 10 January 2027, counted from the schedules lending holds now (the paused one moved out).
        long due = ids.stream().flatMap(id -> lending.currentSchedule(id).instalments().stream())
                .filter(i -> !i.dueDate().isAfter(clock.today())).count();
        assertThat(s.onTimeRepayment().instalmentsDue()).isEqualTo(due).isGreaterThan(0);
        assertThat(s.onTimeRepayment().instalmentsMissed()).isZero();
        assertThat(s.onTimeRepayment().rateBasisPoints()).isEqualTo(10_000);

        assertThat(s.openHardshipCases()).isZero();
        assertThat(s.hardshipOutcomes()).extracting(o -> o.outcome() + ":" + o.cases()).containsExactly("APPLIED:1");

        // Ana saved and borrowed; every other borrower only borrowed. Every borrower was loaned to without a credit score.
        assertThat(s.impact().membersWithLoan()).isEqualTo(loanCount);
        assertThat(s.impact().membersServed()).isEqualTo(loanCount);
        assertThat(s.impact().membersLoanedWithoutCreditScore()).isEqualTo(s.impact().membersWithLoan());
        assertThat(s.impact().loansIssued()).isEqualTo(loanCount);
        long principal = jdbc.sql("SELECT SUM(principal_cents) FROM lending.loan").query(Long.class).single();
        assertThat(s.impact().averageLoanSizeCents()).isEqualTo(Math.round((double) principal / loanCount));
        assertThat(s.impact().firstLoansRepaid()).isEqualTo(1);
        assertThat(s.impact().firstLoansRepaidOnPlan()).isEqualTo(1);
        assertThat(s.impact().firstLoanOnPlanRateBasisPoints()).isEqualTo(10_000);
        assertThat(s.asOf()).isNotNull();
        assertThat(rows(staffGet("/api/v1/desk/portfolio/loans", "OFFICER", "status", "REPAID")).stream().map(r -> r.path("loanId").asText()))
                .containsExactly(anaLoan.toString());
        assertThat(rows(staffGet("/api/v1/desk/portfolio/loans", "OFFICER", "agingBucket", "DPD_8_30"))).isEmpty();
        assertThat(boLoan).isNotNull();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Out of order, replay and rebuild
    // ---------------------------------------------------------------------------------------------------------------

    private void assertOutOfOrderAndRebuild(UUID repaidLoan) throws Exception {
        PortfolioSummary before = desk.portfolioSummary();
        UUID member = jdbc.sql("SELECT member_id FROM lending.loan WHERE id = ?").param(repaidLoan).query(UUID.class).single();

        // An old balance for the repaid loan arrives late, and again: nothing changes.
        RepaymentReceived stale = new RepaymentReceived(Ids.newId(), repaidLoan, member, DISBURSED, Ids.newId(), "CHECKOUT", 15_000, 15_000, 0,
                105_000, "ACTIVE");
        loanProjector.on(stale);
        loanProjector.on(stale);
        PortfolioSummary afterStale = desk.portfolioSummary();
        assertThat(afterStale).usingRecursiveComparison().ignoringFields("asOf").isEqualTo(before);
        assertThat(rowOf(staffGet("/api/v1/desk/portfolio/loans", "OFFICER"), "loanId", repaidLoan).path("status").asText()).isEqualTo("REPAID");

        // Rebuild: empty every event-fed projection, replay the publication log of the desk's listeners, and get the same figures.
        for (Projection p : List.of(Projection.LOANS, Projection.HARDSHIP, Projection.SAVINGS, Projection.APPROVALS)) {
            assertThat(desk.reset(p)).as(p.name()).isGreaterThanOrEqualTo(0);
        }
        assertThat(desk.portfolioSummary().loans()).isZero();
        assertThat(rows(staffGet("/api/v1/desk/portfolio/loans", "OFFICER"))).isEmpty();
        jdbc.sql("UPDATE event_publication SET completion_date = NULL WHERE listener_id LIKE '%deskquery%'").update();
        // Only the desk listeners' publications are incomplete now, so resubmitting everything incomplete replays exactly those.
        incomplete.resubmitIncompletePublications(p -> true);
        settle();

        PortfolioSummary rebuilt = desk.portfolioSummary();
        assertThat(rebuilt).usingRecursiveComparison().ignoringFields("asOf").isEqualTo(before);
        assertThat(staffGet("/api/v1/desk/workload", "OFFICER").path("items")).isNotEmpty(); // the applications are untouched by the reset
        assertThat(rowOf(staffGet("/api/v1/desk/applications", "OFFICER", "status", "DISBURSED"), "memberRef", member).path("savingStreakMonths").asInt())
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Access control and request validation
    // ---------------------------------------------------------------------------------------------------------------

    private static final List<String> PATHS = List.of("/api/v1/desk/applications", "/api/v1/desk/assessments", "/api/v1/desk/portfolio/loans",
            "/api/v1/desk/portfolio/summary", "/api/v1/desk/workload");

    @Test
    void membersAndAnonymousCallersCannotReadTheDeskLists() throws Exception {
        for (String path : PATHS) {
            mvc.perform(get(path).header("X-Seed-Member-Id", UUID.randomUUID().toString())).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (String role : List.of("OFFICER", "CHECKER", "ADMIN")) {
                mvc.perform(staffRequest(get(path), role)).andExpect(status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
            }
        }
    }

    @Test
    void unknownColumnsFiltersAndValuesAreRefusedNotIgnored() throws Exception {
        for (String path : List.of("/api/v1/desk/applications", "/api/v1/desk/assessments", "/api/v1/desk/portfolio/loans")) {
            expect422(path, "desk-query-invalid-sort", "sort", "password");
            expect422(path, "desk-query-invalid-sort", "sort", "status;drop table deskquery.loan_view");
            expect422(path, "desk-query-invalid-sort", "sort", "status:sideways");
            expect422(path, "desk-query-invalid-filter", "email", "ana@example.test");
            expect422(path, "desk-query-invalid-filter", "status", "' OR '1'='1");
            expect422(path, "desk-query-invalid-limit", "limit", "0");
            expect422(path, "desk-query-invalid-limit", "limit", "201");
            expect422(path, "desk-query-invalid-limit", "limit", "many");
            expect422(path, "desk-query-invalid-cursor", "cursor", "not-a-cursor");
        }
        expect422("/api/v1/desk/portfolio/loans", "desk-query-invalid-filter", "autopay", "maybe");
        expect422("/api/v1/desk/portfolio/loans", "desk-query-invalid-filter", "agingBucket", "DPD_999");
        expect422("/api/v1/desk/applications", "desk-query-invalid-filter", "slaBreached", "1");
        // The tables survived every attempt above.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM deskquery.loan_view").query(Integer.class).single()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void filtersThatMatchNothingReturnAnEmptyPageWithAnExactZeroTotal() throws Exception {
        JsonNode page = staffGet("/api/v1/desk/portfolio/loans", "OFFICER", "agingBucket", "DPD_60_PLUS", "hardship", "true", "autopay", "true");
        assertThat(page.path("rows")).isEmpty();
        assertThat(page.has("nextCursor")).isFalse();
        assertThat(page.path("total").path("value").asLong()).isZero();
        assertThat(staffGet("/api/v1/desk/portfolio/summary", "OFFICER").path("byAgingBucket")).hasSize(5);
    }

    private void expect422(String path, String code, String name, String value) throws Exception {
        mvc.perform(staffRequest(get(path), "OFFICER").param(name, value)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.type").value("https://problems.paypal-seed.dev/" + code));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers: the hardship flow
    // ---------------------------------------------------------------------------------------------------------------

    private record HardshipMember(UUID memberId, UUID loanId, UUID caseId) {
    }

    /** Loan of $1,200 over 8 months; income 9 weeks at $200 then 4 weeks at $96: a persistent drop the scorecard flags. */
    private HardshipMember openHardshipCase(String phone) throws Exception {
        UUID member = UUID.randomUUID();
        LoanSummary loan = disburse(member, 1_200, 8, 20, DISBURSED);
        contacts.upsertContact(member, phone, "en", "America/Los_Angeles");
        LocalDate scanDate = lending.currentSchedule(loan.loanId()).instalments().get(0).dueDate().minusDays(8);
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
        UUID caseId = jdbc.sql("SELECT id FROM hardship.hardship_case WHERE loan_id = ?").param(loan.loanId()).query(UUID.class).single();
        settle();
        mvc.perform(post("/internal/jobs/hardship/sweep").header("Authorization", SERVICE_TOKEN)).andExpect(status().isOk());
        settle();
        return new HardshipMember(member, loan.loanId(), caseId);
    }

    /** The apply saga runs just after the checker's decision returns. */
    private void awaitCaseStatus(UUID caseId, String expected) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() ->
                expected.equals(jdbc.sql("SELECT status FROM hardship.hardship_case WHERE id = ?").param(caseId).query(String.class).single()));
    }

    private org.springframework.test.web.servlet.ResultActions decide(UUID caseId, UUID staffId, String roles, String key) throws Exception {
        return mvc.perform(staffRequest(post("/api/v1/desk/hardship/cases/{id}/decision", caseId), staffId, roles)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"reasonCode\":\"INCOME_DROP_VERIFIED\",\"evidenceViewedMs\":30000}"));
    }

    private static String choice(JsonNode offer, String label) {
        for (JsonNode o : offer.path("options")) {
            if (o.path("label").asText().equals(label)) {
                return "{\"offerId\":\"" + offer.path("offerId").asText() + "\",\"optionId\":\"" + o.path("optionId").asText() + "\"}";
            }
        }
        throw new AssertionError("option " + label + " not offered: " + offer);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers: HTTP and JSON
    // ---------------------------------------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder staffRequest(MockHttpServletRequestBuilder request, String roles) {
        return staffRequest(request, UUID.randomUUID(), roles);
    }

    private static MockHttpServletRequestBuilder staffRequest(MockHttpServletRequestBuilder request, UUID staffId, String roles) {
        return request.header("X-Seed-Staff-Id", staffId.toString()).header("X-Seed-Staff-Roles", roles);
    }

    private static MockHttpServletRequestBuilder asMemberRequest(MockHttpServletRequestBuilder request, UUID memberId) {
        return request.header("X-Seed-Member-Id", memberId.toString());
    }

    private String asMember(MockHttpServletRequestBuilder request, UUID memberId) throws Exception {
        MvcResult r = mvc.perform(asMemberRequest(request, memberId)).andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
    }

    private JsonNode staffGet(String path, String roles, String... params) throws Exception {
        MockHttpServletRequestBuilder request = staffRequest(get(path), roles);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult r = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** Follows nextCursor from the first page to the last, as a grid scrolling down would. */
    private List<JsonNode> walk(String path, String sort, int limit) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            List<String> params = new ArrayList<>(List.of("sort", sort, "limit", String.valueOf(limit)));
            if (cursor != null) {
                params.addAll(List.of("cursor", cursor));
            }
            JsonNode page = staffGet(path, "OFFICER", params.toArray(String[]::new));
            assertThat(page.has("total")).as("total only on the first page").isEqualTo(cursor == null);
            page.path("rows").forEach(all::add);
            cursor = page.has("nextCursor") ? page.path("nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 200);
        assertThat(pages).isLessThan(200);
        return all;
    }

    private static List<JsonNode> rows(JsonNode page) {
        List<JsonNode> rows = new ArrayList<>();
        page.path("rows").forEach(rows::add);
        return rows;
    }

    private static List<String> ids(List<JsonNode> rows) {
        return rows.stream().map(r -> r.has("loanId") ? r.path("loanId").asText() : r.has("applicationId") && !r.has("assessmentId")
                ? r.path("applicationId").asText() : r.path("assessmentId").asText()).toList();
    }

    private static JsonNode rowOf(JsonNode page, String field, UUID value) {
        return rows(page).stream().filter(r -> r.path(field).asText().equals(value.toString())).findFirst()
                .orElseThrow(() -> new AssertionError("no row with " + field + "=" + value + " in " + page));
    }

    private static long waiting(JsonNode workload, String kind, String role) {
        long sum = 0;
        for (JsonNode i : workload.path("items")) {
            if (i.path("kind").asText().equals(kind) && i.path("role").asText().equals(role)) {
                sum += i.path("waiting").asLong();
            }
        }
        return sum;
    }

    private static long cellWaiting(JsonNode workload, String kind, String role, String bucket) {
        long sum = 0;
        for (JsonNode i : workload.path("items")) {
            if (i.path("kind").asText().equals(kind) && i.path("role").asText().equals(role) && i.path("slaBucket").asText().equals(bucket)) {
                sum += i.path("waiting").asLong();
            }
        }
        return sum;
    }

    private static List<String> cellBuckets(JsonNode workload, String kind) {
        List<String> buckets = new ArrayList<>();
        for (JsonNode i : workload.path("items")) {
            if (i.path("kind").asText().equals(kind)) {
                buckets.add(i.path("slaBucket").asText());
            }
        }
        return buckets;
    }

    /** Rows come back in the requested order: compare neighbours on the sort columns, then on the id. */
    private static void assertOrdered(List<JsonNode> rows, String sort) {
        String[] columns = sort.split(",");
        Comparator<JsonNode> order = null;
        for (String c : columns) {
            String[] bits = c.split(":");
            String field = bits[0];
            Comparator<JsonNode> next = (x, y) -> compareField(x, y, field);
            if (bits.length == 2 && bits[1].equals("desc")) {
                next = next.reversed();
            }
            order = order == null ? next : order.thenComparing(next);
        }
        boolean lastDesc = columns[columns.length - 1].endsWith(":desc");
        Comparator<JsonNode> byId = Comparator.comparing(r -> r.path("loanId").asText());
        order = order.thenComparing(lastDesc ? byId.reversed() : byId);
        for (int i = 1; i < rows.size(); i++) {
            assertThat(order.compare(rows.get(i - 1), rows.get(i))).as("%s: row %d must not come after row %d", sort, i - 1, i)
                    .isLessThanOrEqualTo(0);
        }
    }

    private static final List<String> BUCKETS = List.of("CURRENT", "DPD_1_7", "DPD_8_30", "DPD_31_60", "DPD_60_PLUS");

    private static int compareField(JsonNode x, JsonNode y, String field) {
        if (field.equals("agingBucket")) {
            return Integer.compare(BUCKETS.indexOf(x.path(field).asText()), BUCKETS.indexOf(y.path(field).asText()));
        }
        if (x.path(field).isNumber() && y.path(field).isNumber()) {
            return Long.compare(x.path(field).asLong(), y.path(field).asLong());
        }
        // An absent date (nextDueDate of a repaid loan) sorts after every real one, as the grid's sentinel does.
        String left = x.path(field).isMissingNode() ? "9999-12-31" : x.path(field).asText();
        String right = y.path(field).isMissingNode() ? "9999-12-31" : y.path(field).asText();
        return left.compareTo(right);
    }

    /** The desk lists carry opaque ids and figures only. */
    private void assertNoPersonalData(JsonNode page) {
        String text = page.toString().toLowerCase();
        assertThat(text).doesNotContain("email").doesNotContain("phone").doesNotContain("\"name\"").doesNotContain("@");
    }
}
