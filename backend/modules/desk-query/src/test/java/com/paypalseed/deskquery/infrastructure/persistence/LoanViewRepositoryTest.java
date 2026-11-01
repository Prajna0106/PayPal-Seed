package com.paypalseed.deskquery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.deskquery.DbFixture;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.LoanRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Total;
import com.paypalseed.deskquery.domain.model.CursorCodec;
import com.paypalseed.deskquery.domain.model.GridQuery;
import com.paypalseed.deskquery.domain.model.Grids;
import com.paypalseed.deskquery.domain.model.NextDue.Instalment;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoanViewRepositoryTest {

    private static final CursorCodec CODEC = new CursorCodec("repository-test-key");
    private static LoanViewRepository loans;
    private static HardshipViewRepository hardship;

    @BeforeAll
    static void connect() {
        loans = new LoanViewRepository(DbFixture.dsl());
        hardship = new HardshipViewRepository(DbFixture.dsl());
    }

    @BeforeEach
    void clean() {
        DbFixture.clean();
    }

    private static UUID loan(int n) {
        return new UUID(1, n);
    }

    private static UUID member(int n) {
        return new UUID(2, n);
    }

    private static List<Instalment> monthly(int count, long each) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new Instalment(i, LocalDate.of(2026, 11, 20).plusMonths(i), each)).toList();
    }

    private static void seed(int n, LocalDate disbursedOn, long principal) {
        loans.ensure(loan(n), member(n));
        loans.initialise(loan(n), principal, disbursedOn);
        loans.applySchedule(loan(n), 1, monthly(4, principal / 4));
    }

    private static GridQuery query(String sort, Map<String, List<String>> params, int limit, String cursor) {
        return Grids.LOANS.parse(sort, params, limit, cursor, 50, 200, CODEC);
    }

    private static List<LoanRow> everything(String sort, Map<String, List<String>> params) {
        return loans.page(query(sort, params, 200, null)).rows();
    }

    /** Follows the cursors page by page, as a grid scrolling down would. */
    private static List<LoanRow> paged(String sort, Map<String, List<String>> params, int pageSize) {
        List<LoanRow> rows = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            GridQuery q = query(sort, params, pageSize, cursor);
            Keyset<LoanRow> page = loans.page(q);
            rows.addAll(page.rows());
            cursor = page.hasMore() ? CODEC.encode(q, page.lastKeys()) : null;
            pages++;
        } while (cursor != null && pages < 100);
        return rows;
    }

    private static void seedMany(int count) {
        LocalDate[] dates = {LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 1)};
        for (int n = 1; n <= count; n++) {
            // Four dates and three principals across the loans: most rows tie on any single sort column.
            seed(n, dates[n % dates.length], 40_000L * (1 + n % 3));
            loans.recordCollectionMode(loan(n), LocalDate.of(2026, 11, 1), n % 2 == 0);
            loans.recordDelinquency(loan(n), LocalDate.of(2026, 11, 1), n % 5 == 0 ? "DPD_8_30" : "CURRENT", n % 5 == 0 ? 12 : 0);
        }
    }

    @Test
    void pagingThroughTiesNeverSkipsOrRepeatsARow() {
        seedMany(23);
        for (String sort : List.of("disbursedOn:desc", "disbursedOn", "principalCents:desc", "outstandingCents",
                "daysLate:desc", "agingBucket:desc", "nextDueDate", "status,daysLate:desc", "daysLate:desc,disbursedOn:asc,principalCents")) {
            List<LoanRow> whole = everything(sort, Map.of());
            List<LoanRow> pages = paged(sort, Map.of(), 5);

            assertThat(pages).as(sort).hasSize(23);
            assertThat(pages.stream().map(LoanRow::loanId).distinct().count()).as(sort).isEqualTo(23);
            assertThat(pages.stream().map(LoanRow::loanId).toList()).as(sort).isEqualTo(whole.stream().map(LoanRow::loanId).toList());
        }
    }

    @Test
    void tiesAreBrokenByTheLoanIdInTheDirectionOfTheLastSortKey() {
        seedMany(12);

        List<UUID> ascending = everything("principalCents", Map.of()).stream().filter(r -> r.principalCents() == 40_000)
                .map(LoanRow::loanId).toList();
        List<UUID> descending = everything("principalCents:desc", Map.of()).stream().filter(r -> r.principalCents() == 40_000)
                .map(LoanRow::loanId).toList();

        assertThat(ascending).isSorted();
        assertThat(descending).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void sortsByAgingSeverityNotByName() {
        seedMany(10);
        loans.recordDelinquency(loan(1), LocalDate.of(2026, 11, 2), "DPD_60_PLUS", 75);
        loans.recordDelinquency(loan(2), LocalDate.of(2026, 11, 2), "DPD_1_7", 3);

        List<String> buckets = everything("agingBucket:desc", Map.of()).stream().map(LoanRow::agingBucket).toList();

        assertThat(buckets.get(0)).isEqualTo("DPD_60_PLUS");
        assertThat(buckets).containsSubsequence("DPD_60_PLUS", "DPD_8_30", "DPD_1_7", "CURRENT");
    }

    @Test
    void aRowInsertedMidScrollNeitherRepeatsNorShiftsLaterPages() {
        seedMany(12);
        GridQuery first = query("disbursedOn:desc", Map.of(), 5, null);
        Keyset<LoanRow> page1 = loans.page(first);

        seed(99, LocalDate.of(2026, 12, 31), 120_000); // sorts before everything already seen
        Keyset<LoanRow> page2 = loans.page(query("disbursedOn:desc", Map.of(), 5, CODEC.encode(first, page1.lastKeys())));

        assertThat(page2.rows()).extracting(LoanRow::loanId).doesNotContainAnyElementsOf(page1.rows().stream().map(LoanRow::loanId).toList())
                .doesNotContain(loan(99));
        assertThat(page2.rows()).hasSize(5);
    }

    @Test
    void filtersCombine() {
        seedMany(20);
        hardship.opened(UUID.randomUUID(), loan(5), member(5), "LIKELY_MISS", "PROACTIVE_SCAN", LocalDate.of(2026, 11, 1));
        loans.refreshHardshipFlag(loan(5));

        assertThat(everything(null, Map.of("agingBucket", List.of("DPD_8_30")))).hasSize(4)
                .allMatch(r -> r.agingBucket().equals("DPD_8_30") && r.daysLate() == 12);
        assertThat(everything(null, Map.of("autopay", List.of("true")))).hasSize(10).allMatch(LoanRow::autopay);
        assertThat(everything(null, Map.of("hardship", List.of("true")))).extracting(LoanRow::loanId).containsExactly(loan(5));
        assertThat(everything(null, Map.of("hardship", List.of("false")))).hasSize(19);
        Map<String, List<String>> both = new LinkedHashMap<>();
        both.put("agingBucket", List.of("DPD_8_30"));
        both.put("autopay", List.of("true"));
        assertThat(everything(null, both)).hasSize(2).allMatch(r -> r.autopay() && r.daysLate() == 12);
        assertThat(everything(null, Map.of("status", List.of("REPAID")))).isEmpty();
    }

    @Test
    void totalIsExactBelowTheCapAndAnEstimateAboveIt() {
        seedMany(30);
        GridQuery q = query(null, Map.of(), 10, null);

        assertThat(loans.total(q, 100)).isEqualTo(new Total(30, true));
        Total estimate = loans.total(q, 10);
        assertThat(estimate.exact()).isFalse();
        assertThat(estimate.value()).isGreaterThan(10);
        assertThat(loans.total(query(null, Map.of("autopay", List.of("true")), 10, null), 100)).isEqualTo(new Total(15, true));
    }

    @Test
    void anOlderMoneyFactNeverOverwritesANewerOne() {
        seed(1, LocalDate.of(2026, 11, 1), 120_000);

        assertThat(loans.recordMoney(loan(1), LocalDate.of(2026, 12, 20), 90_000, "ACTIVE")).isTrue();
        assertThat(loans.recordMoney(loan(1), LocalDate.of(2026, 12, 5), 120_000, "ACTIVE")).isFalse();

        assertThat(everything(null, Map.of()).get(0).outstandingCents()).isEqualTo(90_000);
    }

    @Test
    void aReplayedMoneyFactChangesNothing() {
        seed(1, LocalDate.of(2026, 11, 1), 120_000);
        loans.recordMoney(loan(1), LocalDate.of(2026, 12, 20), 90_000, "ACTIVE");
        LoanRow before = everything(null, Map.of()).get(0);

        loans.recordMoney(loan(1), LocalDate.of(2026, 12, 20), 90_000, "ACTIVE");

        assertThat(everything(null, Map.of()).get(0)).isEqualTo(before);
    }

    @Test
    void aLateRepaidEventCannotUndoAReopening() {
        seed(1, LocalDate.of(2026, 11, 1), 120_000);
        loans.recordRepaid(loan(1), LocalDate.of(2027, 3, 20), true);
        loans.recordReopened(loan(1), LocalDate.of(2027, 3, 22), 15_000);

        assertThat(loans.recordRepaid(loan(1), LocalDate.of(2027, 3, 20), true)).isFalse();

        LoanRow row = everything(null, Map.of()).get(0);
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.outstandingCents()).isEqualTo(15_000);
    }

    @Test
    void delinquencyAndCollectionModeHaveTheirOwnGuards() {
        seed(1, LocalDate.of(2026, 11, 1), 120_000);

        assertThat(loans.recordDelinquency(loan(1), LocalDate.of(2026, 12, 28), "DPD_8_30", 10)).isTrue();
        assertThat(loans.recordDelinquency(loan(1), LocalDate.of(2026, 12, 22), "DPD_1_7", 2)).isFalse();
        assertThat(loans.recordCollectionMode(loan(1), LocalDate.of(2026, 12, 1), true)).isTrue();
        assertThat(loans.recordCollectionMode(loan(1), LocalDate.of(2026, 11, 20), false)).isFalse();
        // A money fact on an earlier date does not block them: the groups are independent.
        assertThat(loans.recordMoney(loan(1), LocalDate.of(2026, 12, 29), 105_000, "ACTIVE")).isTrue();

        LoanRow row = everything(null, Map.of()).get(0);
        assertThat(row.agingBucket()).isEqualTo("DPD_8_30");
        assertThat(row.daysLate()).isEqualTo(10);
        assertThat(row.autopay()).isTrue();
    }

    @Test
    void aNewerScheduleWinsWhateverTheArrivalOrder() {
        loans.ensure(loan(1), member(1));
        List<Instalment> paused = List.of(new Instalment(1, LocalDate.of(2027, 1, 20), 60_000), new Instalment(2, LocalDate.of(2027, 2, 20), 60_000));

        assertThat(loans.applySchedule(loan(1), 2, paused)).isTrue();
        assertThat(loans.applySchedule(loan(1), 1, monthly(4, 30_000))).isFalse();
        loans.initialise(loan(1), 120_000, LocalDate.of(2026, 11, 1));

        LoanRow row = everything(null, Map.of()).get(0);
        assertThat(row.nextDueDate()).isEqualTo(LocalDate.of(2027, 1, 20));
        assertThat(row.nextDueCents()).isEqualTo(60_000L);
        assertThat(row.principalCents()).isEqualTo(120_000);
        assertThat(loans.applySchedule(loan(1), 2, paused)).isFalse();
    }

    @Test
    void nextDueFollowsPaymentsAndTheOutstandingBalance() {
        seed(1, LocalDate.of(2026, 11, 1), 120_000);
        assertThat(everything(null, Map.of()).get(0).nextDueDate()).isEqualTo(LocalDate.of(2026, 12, 20));

        loans.recordMoney(loan(1), LocalDate.of(2026, 12, 20), 75_000, "ACTIVE");

        LoanRow row = everything(null, Map.of()).get(0);
        assertThat(row.nextDueDate()).isEqualTo(LocalDate.of(2027, 1, 20));
        assertThat(row.nextDueCents()).isEqualTo(15_000L);

        loans.recordRepaid(loan(1), LocalDate.of(2027, 3, 1), true);
        assertThat(everything(null, Map.of()).get(0).nextDueDate()).isNull();
    }

    @Test
    void factsThatArriveBeforeTheScheduleDoNotMakeALoanVisible() {
        loans.ensure(loan(1), member(1));
        loans.recordMoney(loan(1), LocalDate.of(2026, 12, 20), 70_000, "ACTIVE");
        loans.recordDelinquency(loan(1), LocalDate.of(2026, 12, 25), "DPD_1_7", 5);

        assertThat(everything(null, Map.of())).isEmpty();
        assertThat(loans.total(query(null, Map.of(), 10, null), 100)).isEqualTo(new Total(0, true));

        loans.initialise(loan(1), 120_000, LocalDate.of(2026, 11, 1));
        loans.applySchedule(loan(1), 1, monthly(4, 30_000));

        LoanRow row = everything(null, Map.of()).get(0);
        assertThat(row.principalCents()).isEqualTo(120_000);
        assertThat(row.outstandingCents()).isEqualTo(70_000); // the earlier money fact is kept, not reset to the principal
        assertThat(row.agingBucket()).isEqualTo("DPD_1_7");
    }

    @Test
    void missedInstalmentsAreCountedOncePerVersionAndSequence() {
        seed(1, LocalDate.of(2026, 11, 1), 120_000);

        loans.recordMissed(loan(1), 1, 1);
        loans.recordMissed(loan(1), 1, 1);
        loans.recordMissed(loan(1), 1, 2);
        loans.recordMissed(loan(1), 2, 2);

        assertThat(DbFixture.dsl().fetchOne("select missed_instalments from deskquery.loan_view").get(0, Integer.class)).isEqualTo(3);
    }

    @Test
    void hardshipFlagFollowsTheCaseWhateverTheOrderOfItsEvents() {
        seed(1, LocalDate.of(2026, 11, 1), 120_000);
        UUID caseId = UUID.randomUUID();

        // The close arrives before the open: the case stays closed and the flag stays off.
        hardship.closed(caseId, loan(1), member(1), LocalDate.of(2026, 12, 2), "APPLIED");
        hardship.opened(caseId, loan(1), member(1), "LIKELY_MISS", "PROACTIVE_SCAN", LocalDate.of(2026, 11, 28));
        loans.refreshHardshipFlag(loan(1));
        assertThat(everything(null, Map.of()).get(0).hardship()).isFalse();

        // A second, open case turns it on; a loan row created after the case still starts with the flag on.
        hardship.opened(UUID.randomUUID(), loan(2), member(2), "WATCH", "MEMBER_HELP", LocalDate.of(2026, 12, 3));
        seed(2, LocalDate.of(2026, 11, 2), 120_000);
        assertThat(everything(null, Map.of("hardship", List.of("true")))).extracting(LoanRow::loanId).containsExactly(loan(2));
    }

    @Test
    void resetEmptiesTheLoanProjectionAndClearsTheHardshipFlag() {
        seedMany(5);
        hardship.opened(UUID.randomUUID(), loan(1), member(1), "WATCH", "MEMBER_HELP", LocalDate.of(2026, 12, 3));
        loans.refreshHardshipFlag(loan(1));
        ProjectionResetRepository reset = new ProjectionResetRepository(DbFixture.dsl());

        assertThat(reset.reset(com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection.HARDSHIP)).isEqualTo(1);
        assertThat(everything(null, Map.of("hardship", List.of("true")))).isEmpty();
        assertThat(reset.reset(com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection.LOANS)).isEqualTo(5);
        assertThat(everything(null, Map.of())).isEmpty();
        assertThat(DbFixture.dsl().fetch("select loan_id from deskquery.loan_instalment_view")).isEmpty();
    }
}
