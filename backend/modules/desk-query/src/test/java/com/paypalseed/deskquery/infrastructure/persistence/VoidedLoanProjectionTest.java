package com.paypalseed.deskquery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.deskquery.DbFixture;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.ApplicationRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.LoanRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.StatusTotal;
import com.paypalseed.deskquery.domain.model.CursorCodec;
import com.paypalseed.deskquery.domain.model.GridQuery;
import com.paypalseed.deskquery.domain.model.Grids;
import com.paypalseed.deskquery.domain.model.NextDue.Instalment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A loan whose payout came back never stood (ADR 0024): the desk shows it as DISBURSEMENT_RETURNED, owing nothing, out of the
 * exposure and inclusion figures, and its application as needing another payout. Every step is a replayable upsert, whatever
 * the order the events arrive in.
 */
class VoidedLoanProjectionTest {

    private static final CursorCodec CODEC = new CursorCodec("voided-loan-test-key");
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static LoanViewRepository loans;
    private static ApplicationViewRepository applications;
    private static SummaryRepository summaries;

    @BeforeAll
    static void connect() {
        loans = new LoanViewRepository(DbFixture.dsl());
        applications = new ApplicationViewRepository(DbFixture.dsl());
        summaries = new SummaryRepository(DbFixture.dsl());
    }

    @BeforeEach
    void clean() {
        DbFixture.clean();
    }

    private static UUID id(int n) {
        return new UUID(7, n);
    }

    private static UUID member(int n) {
        return new UUID(8, n);
    }

    private void liveLoan(int n, long principal) {
        loans.ensure(id(n), member(n));
        loans.initialise(id(n), principal, DAY);
        loans.applySchedule(id(n), 1, List.of(new Instalment(1, DAY.plusMonths(1), principal / 2),
                new Instalment(2, DAY.plusMonths(2), principal - principal / 2)));
    }

    private List<LoanRow> allLoans(Map<String, List<String>> params) {
        GridQuery q = Grids.LOANS.parse(null, params, 50, null, 50, 200, CODEC);
        return loans.page(q).rows();
    }

    private static List<ApplicationRow> applicationRows() {
        GridQuery q = Grids.APPLICATIONS.parse(null, Map.of(), 50, null, 50, 200, CODEC);
        return applications.page(q, NOW, Duration.ofHours(4)).rows();
    }

    private static ApplicationViewRepository.Fact application(int n, String status, Instant changedAt) {
        return new ApplicationViewRepository.Fact(id(n), member(n), status, 120_000L, 8, NOW.minus(Duration.ofDays(2)),
                NOW.minus(Duration.ofDays(1)), null, changedAt);
    }

    @Test
    void aVoidedLoanIsDisbursementReturnedOwesNothingAndIsFoundByThatStatus() {
        liveLoan(1, 120_000);
        liveLoan(2, 60_000);
        loans.recordDelinquency(id(1), DAY, "DAYS_1_30", 5);
        loans.recordCollectionMode(id(1), DAY, true);

        loans.recordVoided(id(1), DAY, "DISBURSEMENT_RETURNED");

        LoanRow voided = allLoans(Map.of("status", List.of("DISBURSEMENT_RETURNED"))).get(0);
        assertThat(voided.loanId()).isEqualTo(id(1));
        assertThat(voided.status()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(voided.outstandingCents()).isZero();
        assertThat(voided.nextDueDate()).isNull();
        assertThat(voided.nextDueCents()).isNull();
        assertThat(voided.daysLate()).isZero();
        assertThat(voided.agingBucket()).isEqualTo("CURRENT");
        assertThat(voided.autopay()).isFalse();
        assertThat(allLoans(Map.of("status", List.of("ACTIVE")))).extracting(LoanRow::loanId).containsExactly(id(2));
        assertThat(allLoans(Map.of())).hasSize(2);
    }

    @Test
    void aCoolingOffCancellationIsCancelled() {
        liveLoan(1, 120_000);

        loans.recordVoided(id(1), DAY, "CANCELLED");

        assertThat(allLoans(Map.of("status", List.of("CANCELLED")))).extracting(LoanRow::loanId).containsExactly(id(1));
    }

    @Test
    void aVoidedLoanLeavesTheExposureTheAgingAndTheInclusionFigures() {
        liveLoan(1, 120_000);
        liveLoan(2, 60_000);
        loans.recordMoney(id(1), DAY, 120_000, "ACTIVE");
        loans.recordMoney(id(2), DAY, 60_000, "ACTIVE");
        loans.recordDelinquency(id(1), DAY, "DAYS_1_30", 5);
        loans.recordMissed(id(1), 1, 1);

        loans.recordVoided(id(1), DAY.plusDays(40), "DISBURSEMENT_RETURNED");

        List<StatusTotal> byStatus = summaries.byStatus();
        assertThat(byStatus).extracting(StatusTotal::status).containsExactly("ACTIVE", "DISBURSEMENT_RETURNED");
        assertThat(byStatus).filteredOn(s -> s.status().equals("DISBURSEMENT_RETURNED"))
                .singleElement().satisfies(s -> {
                    assertThat(s.loans()).isEqualTo(1);
                    assertThat(s.outstandingCents()).isZero();
                });
        assertThat(summaries.byAgingBucket()).as("only the live loan is aged").singleElement()
                .satisfies(b -> assertThat(b.outstandingCents()).isEqualTo(60_000));
        var impact = summaries.impact();
        assertThat(impact.membersWithLoan()).isEqualTo(1);
        assertThat(impact.loansIssued()).isEqualTo(1);
        assertThat(impact.principalTotalCents()).isEqualTo(60_000);
        assertThat(summaries.onTime(DAY.plusDays(90)).instalmentsMissed()).as("a void loan's miss is not counted").isZero();
        assertThat(summaries.onTime(DAY.plusDays(90)).instalmentsDue()).as("nor its instalments").isEqualTo(2);
    }

    @Test
    void nothingThatArrivesLaterBringsAVoidedLoanBack() {
        liveLoan(1, 120_000);
        loans.recordVoided(id(1), DAY, "DISBURSEMENT_RETURNED");

        // late or replayed facts about the loan before it was voided
        loans.recordMoney(id(1), DAY, 90_000, "ACTIVE");
        loans.recordDelinquency(id(1), DAY, "DAYS_1_30", 3);
        loans.recordCollectionMode(id(1), DAY, true);
        loans.recordReopened(id(1), DAY, 50_000);
        loans.applySchedule(id(1), 2, List.of(new Instalment(1, DAY.plusMonths(3), 120_000)));
        loans.recordVoided(id(1), DAY, "DISBURSEMENT_RETURNED"); // the event again

        LoanRow row = allLoans(Map.of()).get(0);
        assertThat(row.status()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(row.outstandingCents()).isZero();
        assertThat(row.nextDueDate()).isNull();
        assertThat(row.daysLate()).isZero();
        assertThat(row.autopay()).isFalse();
    }

    @Test
    void aVoidThatArrivesBeforeTheScheduleStillLeavesAVoidLoan() {
        loans.ensure(id(1), member(1));
        loans.recordVoided(id(1), DAY, "DISBURSEMENT_RETURNED");

        loans.initialise(id(1), 120_000, DAY);
        loans.applySchedule(id(1), 1, List.of(new Instalment(1, DAY.plusMonths(1), 120_000)));

        LoanRow row = allLoans(Map.of()).get(0);
        assertThat(row.status()).isEqualTo("DISBURSEMENT_RETURNED");
        assertThat(row.outstandingCents()).isZero();
        assertThat(row.nextDueDate()).isNull();
    }

    @Test
    void anApprovedApplicationWhoseLoanWasVoidedShowsThePayoutIssueInTheQueue() {
        applications.upsert(application(1, "DISBURSED", NOW.minus(Duration.ofHours(3))));
        applications.upsert(application(2, "APPROVED", NOW.minus(Duration.ofHours(3))));

        // lending announces the return and the application's new state in one transaction; either may arrive first
        applications.recordReturnedPayout(id(1), id(11), id(1) + "-a1", DAY);
        assertThat(rowOf(1).status()).isEqualTo("DISBURSED");
        assertThat(rowOf(1).payoutIssue()).as("not shown until the application is approved again").isNull();
        applications.upsert(application(1, "APPROVED", NOW.minus(Duration.ofHours(2))));

        assertThat(rowOf(1).status()).isEqualTo("APPROVED");
        assertThat(rowOf(1).payoutIssue()).isEqualTo("RETURNED_AFTER_DISBURSEMENT");
        assertThat(rowOf(2).payoutIssue()).as("a plain approved application has no issue").isNull();
        assertThat(applicationRows().stream().filter(r -> "RETURNED_AFTER_DISBURSEMENT".equals(r.payoutIssue())))
                .extracting(ApplicationRow::applicationId).containsExactly(id(1));
        assertThat(applicationFilteredByStatus("APPROVED")).extracting(ApplicationRow::applicationId)
                .containsExactlyInAnyOrder(id(1), id(2));
    }

    @Test
    void theIssueIsKnownEvenWhenTheVoidArrivesBeforeTheApplicationRowAndItGoesWhenTheApplicationMoves() {
        applications.recordReturnedPayout(id(1), id(11), id(1) + "-a1", DAY);
        applications.upsert(application(1, "APPROVED", NOW.minus(Duration.ofHours(2))));
        assertThat(rowOf(1).payoutIssue()).isEqualTo("RETURNED_AFTER_DISBURSEMENT");

        // the same return delivered again changes nothing
        applications.recordReturnedPayout(id(1), id(11), id(1) + "-a1", DAY);
        assertThat(rowOf(1).payoutIssue()).isEqualTo("RETURNED_AFTER_DISBURSEMENT");

        // paid again, or rejected: no issue is shown any more
        applications.upsert(application(1, "DISBURSING", NOW.minus(Duration.ofHours(1))));
        assertThat(rowOf(1).payoutIssue()).isNull();
        applications.upsert(application(1, "REJECTED", NOW));
        assertThat(rowOf(1).payoutIssue()).isNull();
    }

    private static ApplicationRow rowOf(int n) {
        return applicationRows().stream().filter(r -> r.applicationId().equals(id(n))).findFirst().orElseThrow();
    }

    private static List<ApplicationRow> applicationFilteredByStatus(String status) {
        GridQuery q = Grids.APPLICATIONS.parse(null, Map.of("status", List.of(status)), 50, null, 50, 200, CODEC);
        return applications.page(q, NOW, Duration.ofHours(4)).rows();
    }
}
