package com.paypalseed.deskquery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.deskquery.DbFixture;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.ApplicationRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.AssessmentRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.WorkloadItem;
import com.paypalseed.deskquery.domain.model.CursorCodec;
import com.paypalseed.deskquery.domain.model.GridQuery;
import com.paypalseed.deskquery.domain.model.Grids;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueRepositoriesTest {

    private static final CursorCodec CODEC = new CursorCodec("repository-test-key");
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Duration SOON = Duration.ofHours(4);
    private static ApplicationViewRepository applications;
    private static AssessmentViewRepository assessments;
    private static ApprovalViewRepository approvals;
    private static SavingsViewRepository savings;
    private static SummaryRepository summaries;

    @BeforeAll
    static void connect() {
        applications = new ApplicationViewRepository(DbFixture.dsl());
        assessments = new AssessmentViewRepository(DbFixture.dsl());
        approvals = new ApprovalViewRepository(DbFixture.dsl());
        savings = new SavingsViewRepository(DbFixture.dsl());
        summaries = new SummaryRepository(DbFixture.dsl());
    }

    @BeforeEach
    void clean() {
        DbFixture.clean();
    }

    private static UUID id(int n) {
        return new UUID(3, n);
    }

    private static UUID member(int n) {
        return new UUID(2, n);
    }

    private static ApplicationViewRepository.Fact application(int n, String status, Instant submittedAt, Instant changedAt) {
        return new ApplicationViewRepository.Fact(id(n), member(n), status, 50_000L + 10_000L * (n % 3), 6 + n % 4, submittedAt,
                submittedAt.plus(Duration.ofHours(24)), n % 2 == 0 ? "off-" + n % 3 : null, changedAt);
    }

    private static List<ApplicationRow> pagedApplications(String sort, Map<String, List<String>> params, int size) {
        List<ApplicationRow> rows = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            GridQuery q = Grids.APPLICATIONS.parse(sort, params, size, cursor, 50, 200, CODEC);
            Keyset<ApplicationRow> page = applications.page(q, NOW, SOON);
            rows.addAll(page.rows());
            cursor = page.hasMore() ? CODEC.encode(q, page.lastKeys()) : null;
            pages++;
        } while (cursor != null && pages < 100);
        return rows;
    }

    private static List<AssessmentRow> pagedAssessments(String sort, Map<String, List<String>> params, int size) {
        List<AssessmentRow> rows = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            GridQuery q = Grids.ASSESSMENTS.parse(sort, params, size, cursor, 50, 200, CODEC);
            Keyset<AssessmentRow> page = assessments.page(q, NOW);
            rows.addAll(page.rows());
            cursor = page.hasMore() ? CODEC.encode(q, page.lastKeys()) : null;
            pages++;
        } while (cursor != null && pages < 100);
        return rows;
    }

    private void seedApplications(int count) {
        Instant[] submitted = {NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(10)), NOW.minus(Duration.ofHours(1))};
        for (int n = 1; n <= count; n++) {
            String status = n % 4 == 0 ? "PENDING_SECOND_APPROVAL" : n % 7 == 0 ? "DISBURSED" : "SUBMITTED";
            applications.upsert(application(n, status, submitted[n % 3], submitted[n % 3]));
        }
    }

    @Test
    void applicationsPageThroughTiesInEveryAllowedOrder() {
        seedApplications(26);
        for (String sort : List.of("slaDueAt", "submittedAt:desc", "requestedAmountCents:desc", "termMonths", "status,termMonths:desc",
                "assignedOfficer:desc,submittedAt", "assessmentOutcome,savingStreakMonths:desc")) {
            List<ApplicationRow> whole = pagedApplications(sort, Map.of(), 200);
            List<ApplicationRow> pages = pagedApplications(sort, Map.of(), 4);

            assertThat(pages.stream().map(ApplicationRow::applicationId).toList()).as(sort)
                    .containsExactlyElementsOf(whole.stream().map(ApplicationRow::applicationId).toList())
                    .doesNotHaveDuplicates().hasSize(26);
        }
    }

    @Test
    void slaBreachMeansOpenAndPastDue() {
        seedApplications(26);

        List<ApplicationRow> breached = pagedApplications(null, Map.of("slaBreached", List.of("true")), 50);
        List<ApplicationRow> fine = pagedApplications(null, Map.of("slaBreached", List.of("false")), 50);

        assertThat(breached).isNotEmpty().allMatch(r -> r.slaBreached() && r.slaState().equals("OVERDUE")
                && (r.status().equals("SUBMITTED") || r.status().equals("PENDING_SECOND_APPROVAL")));
        assertThat(breached.size() + fine.size()).isEqualTo(26);
        assertThat(fine).noneMatch(ApplicationRow::slaBreached);
        // The disbursed ones are past their original deadline but are not waiting for anyone.
        assertThat(fine).anyMatch(r -> r.status().equals("DISBURSED") && r.slaState().equals("NO_SLA"));
    }

    @Test
    void rowCarriesAgeSlaAndOpaqueReferencesOnly() {
        applications.upsert(application(1, "SUBMITTED", NOW.minus(Duration.ofHours(50)), NOW.minus(Duration.ofHours(50))));

        ApplicationRow row = pagedApplications(null, Map.of(), 10).get(0);

        assertThat(row.ageDays()).isEqualTo(2);
        assertThat(row.slaDueAt()).isEqualTo(NOW.minus(Duration.ofHours(26)));
        assertThat(row.memberRef()).isEqualTo(member(1));
        assertThat(ApplicationRow.class.getRecordComponents()).extracting(c -> c.getName().toLowerCase())
                .noneMatch(n -> n.contains("email") || n.contains("name") || n.contains("phone"));
    }

    @Test
    void anOlderApplicationSnapshotNeverOverwritesANewerOne() {
        Instant submitted = NOW.minus(Duration.ofHours(5));
        assertThat(applications.upsert(application(1, "SUBMITTED", submitted, submitted))).isTrue();
        assertThat(applications.upsert(application(1, "APPROVED", submitted, submitted.plusSeconds(600)))).isTrue();

        assertThat(applications.upsert(application(1, "PENDING_SECOND_APPROVAL", submitted, submitted.plusSeconds(300)))).isFalse();
        assertThat(applications.upsert(application(1, "APPROVED", submitted, submitted.plusSeconds(600)))).isTrue(); // replay

        assertThat(pagedApplications(null, Map.of(), 10)).singleElement().extracting(ApplicationRow::status).isEqualTo("APPROVED");
    }

    @Test
    void savingStreakAndAssessmentOutcomeReachTheApplicationInEitherOrder() {
        // Savings and the assessment were seen before the application.
        savings.setStreak(member(1), 5);
        assessments.upsert(assessment(1, id(1), true, "PENDING", false, NOW.minus(Duration.ofHours(6)), NOW.minus(Duration.ofHours(6))));
        applications.upsert(application(1, "SUBMITTED", NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(5))));
        // The application was seen first, then savings and the assessment.
        applications.upsert(application(2, "SUBMITTED", NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(5))));
        savings.setStreak(member(2), 3);
        applications.setSavingStreak(member(2), 3);
        applications.setAssessmentOutcome(id(2), "NOT_RECOMMENDED");

        List<ApplicationRow> rows = pagedApplications("savingStreakMonths:desc", Map.of(), 10);

        assertThat(rows).extracting(ApplicationRow::applicationId).containsExactly(id(1), id(2));
        assertThat(rows).extracting(ApplicationRow::savingStreakMonths).containsExactly(5, 3);
        assertThat(rows).extracting(ApplicationRow::assessmentOutcome).containsExactly("RECOMMENDED", "NOT_RECOMMENDED");
        assertThat(pagedApplications(null, Map.of("assessmentOutcome", List.of("RECOMMENDED")), 10)).hasSize(1);
    }

    private static AssessmentViewRepository.Fact assessment(int n, UUID applicationId, boolean recommended, String decision,
                                                              boolean disputed, Instant createdAt, Instant changedAt) {
        String status = decision;
        String outcome = decision.equals("PENDING") ? (recommended ? "RECOMMENDED" : "NOT_RECOMMENDED") : decision;
        return new AssessmentViewRepository.Fact(id(100 + n), member(n), applicationId, recommended, 80_000L + 5_000L * (n % 4),
                status, outcome, disputed, createdAt, changedAt);
    }

    @Test
    void assessmentsPageThroughTiesAndFilter() {
        Instant[] created = {NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(1))};
        for (int n = 1; n <= 21; n++) {
            assessments.upsert(assessment(n, null, n % 3 != 0, n % 5 == 0 ? "APPROVED" : "PENDING", n % 4 == 0, created[n % 2], created[n % 2]));
        }

        for (String sort : List.of("createdAt:desc", "createdAt", "recommendedAmountCents:desc", "status,createdAt:desc")) {
            List<AssessmentRow> whole = pagedAssessments(sort, Map.of(), 200);
            List<AssessmentRow> pages = pagedAssessments(sort, Map.of(), 4);
            assertThat(pages.stream().map(AssessmentRow::assessmentId).toList()).as(sort)
                    .containsExactlyElementsOf(whole.stream().map(AssessmentRow::assessmentId).toList())
                    .doesNotHaveDuplicates().hasSize(21);
        }
        assertThat(pagedAssessments(null, Map.of("disputed", List.of("true")), 50)).hasSize(5).allMatch(AssessmentRow::disputed);
        assertThat(pagedAssessments(null, Map.of("status", List.of("APPROVED")), 50)).hasSize(4);
        assertThat(pagedAssessments(null, Map.of("recommended", List.of("false")), 50)).hasSize(7).noneMatch(AssessmentRow::recommended);
        assertThat(pagedAssessments(null, Map.of(), 50).get(0).ageDays()).isEqualTo(1);
    }

    @Test
    void aDisputeOnceRaisedIsNeverCleared() {
        Instant created = NOW.minus(Duration.ofDays(2));
        assessments.upsert(assessment(1, null, true, "PENDING", false, created, created));
        assessments.upsert(assessment(1, null, true, "PENDING", true, created, created.plusSeconds(60)));

        // A later snapshot, built before the dispute was read, says "not disputed": the flag stays.
        assessments.upsert(assessment(1, null, true, "APPROVED", false, created, created.plusSeconds(3600)));

        // An older snapshot carrying a dispute is stale for everything but the sticky flag.
        assessments.upsert(assessment(2, null, true, "APPROVED", false, created, created.plusSeconds(3600)));
        assertThat(assessments.upsert(assessment(2, null, true, "PENDING", true, created, created.plusSeconds(10)))).isFalse();
        assessments.markDisputed(id(102));

        List<AssessmentRow> rows = pagedAssessments("recommendedAmountCents", Map.of(), 10);
        assertThat(rows).extracting(AssessmentRow::disputed).containsOnly(true);
        assertThat(rows).extracting(AssessmentRow::status).containsOnly("APPROVED");
    }

    @Test
    void approvalsMoveForwardOnlyWhateverTheArrivalOrder() {
        UUID open = id(1);
        UUID done = id(2);
        UUID checker = id(3);
        Instant sla = NOW.plus(Duration.ofHours(2));

        // 1: requested, then maker approved (the request stays open for the checker).
        approvals.requested(open, id(11), "MAKER_CHECKER", sla);
        approvals.decided(open, id(11), "OPEN", ApprovalViewRepository.WAITING_FOR_CHECKER);
        // 2: the final decision arrives before the request, then the request arrives late and must not reopen it.
        approvals.decided(done, id(12), "APPROVED", ApprovalViewRepository.FINISHED);
        approvals.requested(done, id(12), "MAKER_CHECKER", sla);
        // 3: the maker's decision arrives after the checker's.
        approvals.requested(checker, id(13), "MAKER_CHECKER", sla);
        approvals.decided(checker, id(13), "REJECTED", ApprovalViewRepository.FINISHED);
        approvals.decided(checker, id(13), "OPEN", ApprovalViewRepository.WAITING_FOR_CHECKER);

        List<WorkloadItem> items = summaries.workload(NOW, SOON);

        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo("HARDSHIP_DECISION");
            assertThat(i.role()).isEqualTo("CHECKER");
            assertThat(i.slaBucket()).isEqualTo("DUE_SOON");
            assertThat(i.waiting()).isEqualTo(1);
        });
    }

    @Test
    void workloadCountsApplicationsAndApprovalsByRoleAndSlaBucket() {
        // Applications: submitted 30h ago is overdue, 22h ago is due soon (deadline in 2h), 1h ago is on track.
        applications.upsert(application(1, "SUBMITTED", NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(30))));
        applications.upsert(application(2, "SUBMITTED", NOW.minus(Duration.ofHours(22)), NOW.minus(Duration.ofHours(22))));
        applications.upsert(application(3, "SUBMITTED", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1))));
        applications.upsert(application(4, "PENDING_SECOND_APPROVAL", NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(2))));
        applications.upsert(application(5, "APPROVED", NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(1))));
        // Approvals: one overdue maker step, one with no SLA known yet.
        approvals.requested(id(21), id(31), "MAKER_CHECKER", NOW.minusSeconds(1));
        approvals.decided(id(22), id(32), "OPEN", ApprovalViewRepository.WAITING_FOR_MAKER);

        List<WorkloadItem> items = summaries.workload(NOW, SOON);

        assertThat(cell(items, "LOAN_APPLICATION", "OFFICER", "OVERDUE")).isEqualTo(1);
        assertThat(cell(items, "LOAN_APPLICATION", "OFFICER", "DUE_SOON")).isEqualTo(1);
        assertThat(cell(items, "LOAN_APPLICATION", "OFFICER", "ON_TRACK")).isEqualTo(1);
        assertThat(cell(items, "LOAN_APPLICATION", "CHECKER", "OVERDUE")).isEqualTo(1);
        assertThat(cell(items, "HARDSHIP_DECISION", "OFFICER", "OVERDUE")).isEqualTo(1);
        assertThat(cell(items, "HARDSHIP_DECISION", "OFFICER", "NO_SLA")).isEqualTo(1);
        assertThat(items.stream().mapToLong(WorkloadItem::waiting).sum()).isEqualTo(6);
    }

    private static long cell(List<WorkloadItem> items, String kind, String role, String bucket) {
        return items.stream().filter(i -> i.kind().equals(kind) && i.role().equals(role) && i.slaBucket().equals(bucket))
                .mapToLong(WorkloadItem::waiting).sum();
    }

    @Test
    void resetEmptiesOneProjectionAndRepairsWhatItFed() {
        savings.setStreak(member(1), 4);
        assessments.upsert(assessment(1, id(1), true, "PENDING", false, NOW.minus(Duration.ofHours(6)), NOW.minus(Duration.ofHours(6))));
        applications.upsert(application(1, "SUBMITTED", NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(5))));
        ProjectionResetRepository reset = new ProjectionResetRepository(DbFixture.dsl());

        assertThat(reset.reset(com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection.SAVINGS)).isEqualTo(1);
        assertThat(pagedApplications(null, Map.of(), 10).get(0).savingStreakMonths()).isZero();
        assertThat(reset.reset(com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection.ASSESSMENTS)).isEqualTo(1);
        assertThat(pagedApplications(null, Map.of(), 10).get(0).assessmentOutcome()).isNull();
        assertThat(reset.reset(com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection.APPLICATIONS)).isEqualTo(1);
        assertThat(pagedApplications(null, Map.of(), 10)).isEmpty();
        assertThat(reset.reset(com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection.APPROVALS)).isZero();
    }
}
