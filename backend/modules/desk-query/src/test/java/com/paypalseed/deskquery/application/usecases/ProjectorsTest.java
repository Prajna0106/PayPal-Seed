package com.paypalseed.deskquery.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.deskquery.DbFixture;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.PortfolioSummary;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Workload;
import com.paypalseed.deskquery.domain.model.Grids;
import com.paypalseed.deskquery.infrastructure.persistence.ApplicationViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.ApprovalViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.AssessmentViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.HardshipViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.LoanViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.SavingsViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.SummaryRepository;
import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalDecided;
import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalRequested;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipApplied;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipCaseClosed;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipCaseOpened;
import com.paypalseed.lending.api.events.LendingEvents.CollectionModeChanged;
import com.paypalseed.lending.api.events.LendingEvents.InstalmentMissed;
import com.paypalseed.lending.api.events.LendingEvents.InstalmentView;
import com.paypalseed.lending.api.events.LendingEvents.LateBucketChanged;
import com.paypalseed.lending.api.events.LendingEvents.LoanRepaid;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleGenerated;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.api.events.GoalCreatedEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The projectors against a real database: the figures they produce, and that order and repetition do not change them. */
class ProjectorsTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 3, 1);
    private static final UUID ANA = new UUID(2, 1);
    private static final UUID BO = new UUID(2, 2);
    private static final UUID DEE = new UUID(2, 4);
    private static final UUID LOAN_A = new UUID(1, 1);
    private static final UUID LOAN_B = new UUID(1, 2);
    private static final UUID LOAN_C = new UUID(1, 3);
    private static final UUID CASE_B = new UUID(4, 2);
    private static final UUID CASE_OLD = new UUID(4, 9);

    private static LoanProjector loans;
    private static HardshipProjector hardship;
    private static SavingsProjector savings;
    private static ApprovalProjector approvals;
    private static ApplicationProjector applications;
    private static AssessmentProjector assessments;
    private static PortfolioQueries portfolio;
    private static DeskQueryFacadeService facade;
    private static FakeBusinessClock clock;

    @BeforeAll
    static void wire() {
        var dsl = DbFixture.dsl();
        LoanViewRepository loanRepo = new LoanViewRepository(dsl);
        ApplicationViewRepository applicationRepo = new ApplicationViewRepository(dsl);
        DeskQuerySettings settings = new DeskQuerySettings(Duration.ofHours(24), Duration.ofHours(4), 50, 200, 10_000, "");
        clock = new FakeBusinessClock(TODAY);
        loans = new LoanProjector(loanRepo);
        hardship = new HardshipProjector(new HardshipViewRepository(dsl), loans);
        savings = new SavingsProjector(new SavingsViewRepository(dsl), applicationRepo);
        approvals = new ApprovalProjector(new ApprovalViewRepository(dsl));
        applications = new ApplicationProjector(applicationRepo, settings);
        assessments = new AssessmentProjector(new AssessmentViewRepository(dsl), applicationRepo);
        portfolio = new PortfolioQueries(new SummaryRepository(dsl), settings, clock);
        facade = new DeskQueryFacadeService(new com.paypalseed.deskquery.infrastructure.persistence.ProjectionResetRepository(dsl), portfolio);
    }

    @BeforeEach
    void clean() {
        DbFixture.clean();
        clock.set(TODAY);
    }

    private static List<InstalmentView> schedule(LocalDate first) {
        return List.of(new InstalmentView(1, first, 30_000), new InstalmentView(2, first.plusMonths(1), 30_000),
                new InstalmentView(3, first.plusMonths(2), 30_000), new InstalmentView(4, first.plusMonths(3), 30_000));
    }

    private static RepaymentReceived paid(LocalDate on, long outstanding, String status) {
        return new RepaymentReceived(Ids.newId(), LOAN_A, ANA, on, Ids.newId(), "AUTOPAY", 30_000, 30_000, 0, outstanding, status);
    }

    /** Ana repays her first loan on plan and takes a second; Bo misses an instalment and gets hardship help; Dee only saves. */
    private static List<Consumer<Void>> story() {
        List<Consumer<Void>> events = new ArrayList<>();
        LocalDate d1 = LocalDate.of(2026, 11, 20);
        events.add(v -> loans.on(new ScheduleGenerated(Ids.newId(), LOAN_A, ANA, LocalDate.of(2026, 11, 20), 1, schedule(d1.plusMonths(1)))));
        events.add(v -> loans.on(new CollectionModeChanged(Ids.newId(), LOAN_A, ANA, LocalDate.of(2026, 11, 21), "MANUAL", "AUTOPAY", "MEMBER")));
        events.add(v -> loans.on(paid(LocalDate.of(2026, 12, 20), 90_000, "ACTIVE")));
        events.add(v -> loans.on(paid(LocalDate.of(2027, 1, 20), 60_000, "ACTIVE")));
        events.add(v -> loans.on(paid(LocalDate.of(2027, 2, 20), 30_000, "ACTIVE")));
        events.add(v -> loans.on(paid(LocalDate.of(2027, 2, 25), 0, "REPAID")));
        events.add(v -> loans.on(new LoanRepaid(Ids.newId(), LOAN_A, ANA, LocalDate.of(2027, 2, 25), 4, 0, 0)));

        events.add(v -> loans.on(new ScheduleGenerated(Ids.newId(), LOAN_B, BO, LocalDate.of(2026, 11, 22), 1, schedule(d1.plusMonths(1)))));
        events.add(v -> loans.on(new InstalmentMissed(Ids.newId(), LOAN_B, BO, LocalDate.of(2026, 12, 26), 1, 1, LocalDate.of(2026, 12, 20))));
        events.add(v -> loans.on(new LateBucketChanged(Ids.newId(), LOAN_B, BO, LocalDate.of(2027, 1, 2), "CURRENT", "DPD_8_30", 12, "COB")));
        events.add(v -> hardship.on(new HardshipCaseOpened(Ids.newId(), CASE_B, LOAN_B, BO, LocalDate.of(2027, 1, 3), "PROACTIVE_SCAN", 2,
                "LIKELY_MISS", 40, "v1")));
        events.add(v -> hardship.on(new HardshipCaseOpened(Ids.newId(), CASE_OLD, LOAN_A, ANA, LocalDate.of(2026, 12, 1), "MEMBER_HELP", 1,
                "WATCH", 20, "v1")));
        events.add(v -> hardship.on(new HardshipApplied(Ids.newId(), CASE_OLD, LOAN_A, ANA, LocalDate.of(2026, 12, 4), 2, "PAUSE", "ref",
                List.of(1))));
        events.add(v -> hardship.on(new HardshipCaseClosed(Ids.newId(), CASE_OLD, LOAN_A, ANA, LocalDate.of(2026, 12, 5), "APPLIED")));

        events.add(v -> loans.on(new ScheduleGenerated(Ids.newId(), LOAN_C, ANA, LocalDate.of(2027, 1, 1), 1, schedule(LocalDate.of(2027, 1, 15)))));

        events.add(v -> savings.on(new GoalCreatedEvent(Ids.newId(), ANA.toString(), Ids.newId(), "oven", Money.usd(75), Money.usd(900),
                Instant.parse("2026-08-02T10:00:00Z"))));
        for (String ts : List.of("2026-08-05T10:00:00Z", "2026-09-05T10:00:00Z", "2026-10-05T10:00:00Z", "2026-10-20T10:00:00Z")) {
            events.add(v -> savings.on(new DepositReceivedEvent(Ids.newId(), ANA.toString(), Ids.newId(), Money.usd(75), "cap-" + ts,
                    Instant.parse(ts))));
        }
        events.add(v -> savings.on(new DepositReceivedEvent(Ids.newId(), DEE.toString(), Ids.newId(), Money.usd(20), "cap-dee",
                Instant.parse("2026-10-05T10:00:00Z"))));
        return events;
    }

    private static void run(List<Consumer<Void>> events) {
        events.forEach(e -> e.accept(null));
    }

    @Test
    void theStoryProducesTheFiguresTheDeskReports() {
        run(story());

        PortfolioSummary s = portfolio.summary();

        assertThat(s.loans()).isEqualTo(3);
        assertThat(s.byStatus()).extracting(t -> t.status() + ":" + t.loans() + ":" + t.outstandingCents())
                .containsExactly("ACTIVE:2:240000", "REPAID:1:0");
        assertThat(s.outstandingCents()).isEqualTo(240_000);
        assertThat(s.byAgingBucket()).extracting(b -> b.bucket() + ":" + b.loans())
                .containsExactly("CURRENT:1", "DPD_1_7:0", "DPD_8_30:1", "DPD_31_60:0", "DPD_60_PLUS:0");
        // Instalments due by 1 March 2027: A has 3, B has 3, C has 2; one was missed.
        assertThat(s.onTimeRepayment().instalmentsDue()).isEqualTo(8);
        assertThat(s.onTimeRepayment().instalmentsMissed()).isEqualTo(1);
        assertThat(s.onTimeRepayment().rateBasisPoints()).isEqualTo(8_750);
        assertThat(s.openHardshipCases()).isEqualTo(1);
        assertThat(s.hardshipOutcomes()).extracting(o -> o.outcome() + ":" + o.cases()).containsExactly("APPLIED:1");
        // Ana (saver and borrower), Bo (borrower) and Dee (saver only) were served; two members got loans; no credit score used.
        assertThat(s.impact().membersServed()).isEqualTo(3);
        assertThat(s.impact().membersWithLoan()).isEqualTo(2);
        assertThat(s.impact().membersLoanedWithoutCreditScore()).isEqualTo(2);
        assertThat(s.impact().loansIssued()).isEqualTo(3);
        assertThat(s.impact().averageLoanSizeCents()).isEqualTo(120_000);
        // Ana's first loan (A) was repaid with nothing missed; Bo's first loan is still running.
        assertThat(s.impact().firstLoansRepaid()).isEqualTo(1);
        assertThat(s.impact().firstLoansRepaidOnPlan()).isEqualTo(1);
        assertThat(s.impact().firstLoanOnPlanRateBasisPoints()).isEqualTo(10_000);
        assertThat(s.asOf()).isNotNull();
    }

    @Test
    void replayingEveryEventTwiceInReverseOrderGivesTheSameFigures() {
        run(story());
        PortfolioSummary expected = portfolio.summary();
        DbFixture.clean();

        List<Consumer<Void>> events = story();
        Collections.reverse(events);
        run(events);
        run(events);

        assertThat(portfolio.summary()).usingRecursiveComparison().ignoringFields("asOf").isEqualTo(expected);
    }

    @Test
    void anyShuffleOfTheEventsGivesTheSameFigures() {
        run(story());
        PortfolioSummary expected = portfolio.summary();
        for (long seed = 1; seed <= 5; seed++) {
            DbFixture.clean();
            List<Consumer<Void>> events = story();
            Collections.shuffle(events, new java.util.Random(seed));
            run(events);

            assertThat(portfolio.summary()).as("shuffle " + seed).usingRecursiveComparison().ignoringFields("asOf").isEqualTo(expected);
        }
    }

    @Test
    void streakCountsConsecutiveDepositMonthsOnly() {
        run(story());
        long ana = com.paypalseed.deskquery.DbFixture.dsl().fetchOne("select streak_months from deskquery.member_savings where member_ref = ?", ANA)
                .get(0, Long.class);
        long dee = com.paypalseed.deskquery.DbFixture.dsl().fetchOne("select streak_months from deskquery.member_savings where member_ref = ?", DEE)
                .get(0, Long.class);

        assertThat(ana).isEqualTo(3); // August, September, October (two deposits in October count once)
        assertThat(dee).isEqualTo(1);
    }

    @Test
    void workloadSeparatesApplicationsFromHardshipDecisionsAndAgesWithTheClock() {
        Instant now = clock.now();
        applications.apply(new ApplicationProjector.Snapshot(new UUID(3, 1), ANA, "SUBMITTED", 120_000, 8, now.minus(Duration.ofHours(30)),
                null, now.minus(Duration.ofHours(30))));
        applications.apply(new ApplicationProjector.Snapshot(new UUID(3, 2), BO, "PENDING_SECOND_APPROVAL", 90_000, 6,
                now.minus(Duration.ofHours(2)), "off-1", now.minus(Duration.ofHours(1))));
        approvals.on(new ApprovalRequested(Ids.newId(), new UUID(5, 1), CASE_B, "MAKER_CHECKER", now.plus(Duration.ofHours(1))));
        approvals.on(new ApprovalRequested(Ids.newId(), new UUID(5, 2), CASE_OLD, "MAKER_CHECKER", now.plus(Duration.ofDays(1))));
        approvals.on(new ApprovalDecided(Ids.newId(), new UUID(5, 2), CASE_OLD, "MAKER", "APPROVE", "OPEN", "OK", "staff-ab12"));
        approvals.on(new ApprovalDecided(Ids.newId(), new UUID(5, 3), CASE_OLD, "POLICY", "APPROVE", "APPROVED", "WITHIN_POLICY", "policy"));

        Workload w = portfolio.workload();

        assertThat(w.items()).extracting(i -> i.kind() + "/" + i.role() + "/" + i.slaBucket() + "=" + i.waiting()).containsExactlyInAnyOrder(
                "HARDSHIP_DECISION/CHECKER/ON_TRACK=1",
                "HARDSHIP_DECISION/OFFICER/DUE_SOON=1",
                "LOAN_APPLICATION/CHECKER/ON_TRACK=1",
                "LOAN_APPLICATION/OFFICER/OVERDUE=1");
        assertThat(w.byRole()).extracting(r -> r.role() + ":" + r.waiting() + ":" + r.overdue())
                .containsExactly("CHECKER:2:0", "OFFICER:2:1");
    }

    @Test
    void applicationSlaStartsAtSubmissionAndStaleSnapshotsAreIgnored() {
        Instant submitted = Instant.parse("2026-11-10T09:00:00Z");
        UUID id = new UUID(3, 7);
        assertThat(applications.apply(new ApplicationProjector.Snapshot(id, ANA, "SUBMITTED", 120_000, 8, submitted, null, submitted))).isTrue();
        assertThat(applications.apply(new ApplicationProjector.Snapshot(id, ANA, "PENDING_SECOND_APPROVAL", 120_000, 8, submitted, "off-1",
                submitted.plusSeconds(60)))).isTrue();

        assertThat(applications.apply(new ApplicationProjector.Snapshot(id, ANA, "SUBMITTED", 120_000, 8, submitted, null, submitted))).isFalse();

        var row = DbFixture.dsl().fetchOne("select status, sla_due_at, assigned_officer_ref from deskquery.application_view");
        assertThat(row.get(0, String.class)).isEqualTo("PENDING_SECOND_APPROVAL");
        assertThat(row.get(1, java.time.OffsetDateTime.class).toInstant()).isEqualTo(submitted.plus(Duration.ofHours(24)));
        assertThat(row.get(2, String.class)).isEqualTo("off-1");
    }

    @Test
    void anApplicationWhosePayoutIsInFlightLeavesTheOfficerQueueButStaysListed() {
        Instant submitted = Instant.parse("2026-11-10T09:00:00Z");
        UUID id = new UUID(3, 9);
        applications.apply(new ApplicationProjector.Snapshot(id, ANA, "APPROVED", 120_000, 8, submitted, "off-2", submitted.plusSeconds(60)));
        assertThat(applications.apply(new ApplicationProjector.Snapshot(id, ANA, "DISBURSING", 120_000, 8, submitted, "off-2",
                submitted.plusSeconds(120)))).isTrue();

        assertThat(Grids.APPLICATION_STATUSES).contains("DISBURSING");
        assertThat(Grids.OPEN_APPLICATION_STATUSES).doesNotContain("DISBURSING");
        assertThat(DbFixture.dsl().fetchValue("select status from deskquery.application_view where application_id = ?", id)).isEqualTo("DISBURSING");
        assertThat(portfolio.workload().items()).as("no officer has anything to do on it").isEmpty();

        // The payout comes back and the application is approved again; the older DISBURSING snapshot, redelivered, changes nothing.
        applications.apply(new ApplicationProjector.Snapshot(id, ANA, "APPROVED", 120_000, 8, submitted, "off-2", submitted.plusSeconds(180)));
        assertThat(applications.apply(new ApplicationProjector.Snapshot(id, ANA, "DISBURSING", 120_000, 8, submitted, "off-2",
                submitted.plusSeconds(120)))).isFalse();
        assertThat(DbFixture.dsl().fetchValue("select status from deskquery.application_view where application_id = ?", id)).isEqualTo("APPROVED");
    }

    @Test
    void assessmentSnapshotsDriveTheQueueAndTheApplicationsOutcome() {
        Instant created = Instant.parse("2026-11-10T09:00:00Z");
        UUID application = new UUID(3, 8);
        UUID assessment = new UUID(6, 8);
        applications.apply(new ApplicationProjector.Snapshot(application, ANA, "SUBMITTED", 120_000, 8, created, null, created));

        assertThat(assessments.apply(new AssessmentProjector.Snapshot(assessment, ANA, application, true, 120_000, "PENDING", false, created,
                created))).isTrue();
        assertThat(outcomeOf(application)).isEqualTo("RECOMMENDED");

        assertThat(assessments.apply(new AssessmentProjector.Snapshot(assessment, ANA, application, true, 120_000, "DECLINED", true, created,
                created.plusSeconds(600)))).isTrue();
        assertThat(outcomeOf(application)).isEqualTo("DECLINED");

        // A replay of the earlier snapshot changes neither the status nor the outcome.
        assertThat(assessments.apply(new AssessmentProjector.Snapshot(assessment, ANA, application, true, 120_000, "PENDING", false, created,
                created))).isFalse();
        assertThat(outcomeOf(application)).isEqualTo("DECLINED");
        assertThat(DbFixture.dsl().fetchOne("select status || ':' || disputed from deskquery.assessment_view").get(0, String.class))
                .isEqualTo("DECLINED:true");
    }

    @Test
    void resetThroughTheFacadeEmptiesTheProjectionAndTheSummaryShowsIt() {
        run(story());

        assertThat(facade.reset(com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection.LOANS)).isEqualTo(3);
        PortfolioSummary s = facade.portfolioSummary();

        assertThat(s.loans()).isZero();
        assertThat(s.impact().membersServed()).isEqualTo(2); // the savers remain: Ana and Dee
        assertThat(s.onTimeRepayment().rateBasisPoints()).isNull();
        assertThat(facade.workload().items()).isEmpty();
    }

    private static String outcomeOf(UUID application) {
        return DbFixture.dsl().fetchOne("select assessment_outcome from deskquery.application_view where application_id = ?", application)
                .get(0, String.class);
    }
}
