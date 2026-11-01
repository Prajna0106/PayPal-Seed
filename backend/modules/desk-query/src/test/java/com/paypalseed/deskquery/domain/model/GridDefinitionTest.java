package com.paypalseed.deskquery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.DomainException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GridDefinitionTest {

    private static final String ID = "0197f3a4-1b2c-7d3e-8f40-5a6b7c8d9e10";
    private final CursorCodec codec = new CursorCodec("unit-test-key-0123456789abcdef");
    private final GridDefinition apps = Grids.APPLICATIONS;

    private GridQuery parse(String sort, Map<String, List<String>> params, Integer limit, String cursor) {
        return apps.parse(sort, params, limit, cursor, 50, 200, codec);
    }

    private static Map<String, List<String>> params(String... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.computeIfAbsent(kv[i], k -> new java.util.ArrayList<>()).add(kv[i + 1]);
        }
        return m;
    }

    private static void assertRefused(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(code));
    }

    @Test
    void usesTheDefaultSortAndPageSize() {
        GridQuery q = parse(null, Map.of(), null, null);

        assertThat(q.sort()).containsExactly(new SortKey("slaDueAt", false));
        assertThat(q.limit()).isEqualTo(50);
        assertThat(q.firstPage()).isTrue();
        assertThat(q.filters()).isEmpty();
    }

    @Test
    void acceptsAllowListedSortsInEitherDirection() {
        GridQuery q = parse("status:desc,requestedAmountCents,termMonths:asc", Map.of(), null, null);

        assertThat(q.sort()).containsExactly(new SortKey("status", true), new SortKey("requestedAmountCents", false),
                new SortKey("termMonths", false));
        assertThat(q.sortSpec()).isEqualTo("status:desc,requestedAmountCents:asc,termMonths:asc");
    }

    @Test
    void rejectsSortColumnsThatAreNotOnTheAllowList() {
        // Database column names, other tables' columns, expressions and injection attempts are all just unknown names.
        for (String bad : List.of("submitted_at", "application_id", "memberRef", "password", "1", "submittedAt;drop table x",
                "submittedAt desc", "submittedAt) desc, (select 1", "SUBMITTEDAT", "status:sideways", "status:asc:desc", ":asc",
                "status,", ",status")) {
            assertRefused(() -> parse(bad, Map.of(), null, null), "desk-query-invalid-sort");
        }
    }

    @Test
    void rejectsRepeatedAndTooManySortColumns() {
        assertRefused(() -> parse("status,status:desc", Map.of(), null, null), "desk-query-invalid-sort");
        assertRefused(() -> parse("status,termMonths,submittedAt,slaDueAt", Map.of(), null, null), "desk-query-invalid-sort");
    }

    @Test
    void parsesFiltersAndSplitsCommaLists() {
        GridQuery q = parse(null, params("status", "SUBMITTED,APPROVED", "status", "REJECTED", "slaBreached", "true"), null, null);

        assertThat(q.values("status")).containsExactly("APPROVED", "REJECTED", "SUBMITTED");
        assertThat(q.flag("slaBreached")).isTrue();
        assertThat(q.flag("assessmentOutcome")).isNull();
    }

    @Test
    void ignoresBlankFilterValues() {
        GridQuery q = parse(null, params("status", ""), null, null);

        assertThat(q.hasFilter("status")).isFalse();
    }

    @Test
    void rejectsUnknownFilterNames() {
        for (String bad : List.of("memberRef", "member_ref", "status; drop table x", "1=1", "email", "name", "STATUS")) {
            assertRefused(() -> parse(null, params(bad, "x"), null, null), "desk-query-invalid-filter");
        }
    }

    @Test
    void rejectsFilterValuesOutsideTheAllowedSet() {
        for (String bad : List.of("' OR '1'='1", "SUBMITTED' --", "SUBMITTED;DROP TABLE deskquery.loan_view", "submitted", "%", "*",
                "SUBMITTED OR 1=1", "\" or \"\"=\"", "1", "null")) {
            assertRefused(() -> parse(null, params("status", bad), null, null), "desk-query-invalid-filter");
        }
        assertRefused(() -> parse(null, params("assessmentOutcome", "RECOMMENDED,MAYBE"), null, null), "desk-query-invalid-filter");
    }

    @Test
    void booleanFiltersAcceptOnlyTrueOrFalseOnce() {
        for (String bad : List.of("yes", "1", "TRUE", "true,false", "true' or '1'='1")) {
            assertRefused(() -> parse(null, params("slaBreached", bad), null, null), "desk-query-invalid-filter");
        }
        assertThat(parse(null, params("slaBreached", "false"), null, null).flag("slaBreached")).isFalse();
    }

    @Test
    void checksThePageSize() {
        assertThat(parse(null, Map.of(), 200, null).limit()).isEqualTo(200);
        assertRefused(() -> parse(null, Map.of(), 0, null), "desk-query-invalid-limit");
        assertRefused(() -> parse(null, Map.of(), 201, null), "desk-query-invalid-limit");
        assertRefused(() -> parse(null, Map.of(), -5, null), "desk-query-invalid-limit");
    }

    @Test
    void reservedParametersAreNotFilters() {
        GridQuery q = parse("status", params("sort", "status", "limit", "10", "cursor", ""), null, null);

        assertThat(q.filters()).isEmpty();
    }

    @Test
    void decodesACursorIssuedForTheSameRequest() {
        GridQuery first = parse("termMonths:desc", params("status", "SUBMITTED"), 10, null);
        String cursor = codec.encode(first, List.of("12", ID));

        GridQuery next = parse("termMonths:desc", params("status", "SUBMITTED"), 10, cursor);

        assertThat(next.firstPage()).isFalse();
        assertThat(next.after()).containsExactly("12", ID);
    }

    @Test
    void refusesACursorIssuedForAnotherRequest() {
        GridQuery first = parse("termMonths:desc", params("status", "SUBMITTED"), 10, null);
        String cursor = codec.encode(first, List.of("12", ID));

        assertRefused(() -> parse("termMonths:asc", params("status", "SUBMITTED"), 10, cursor), "desk-query-invalid-cursor");
        assertRefused(() -> parse("termMonths:desc", params("status", "APPROVED"), 10, cursor), "desk-query-invalid-cursor");
        assertRefused(() -> Grids.ASSESSMENTS.parse("createdAt:desc", Map.of(), 10, cursor, 50, 200, codec),
                "desk-query-invalid-cursor");
    }

    @Test
    void refusesAValidlySignedCursorWhoseValuesDoNotFitTheSortColumns() {
        GridQuery first = parse("termMonths", Map.of(), 10, null);
        // Signed by us, but a text where the INT sort key goes, and one key too few.
        assertRefused(() -> parse("termMonths", Map.of(), 10, codec.encode(first, List.of("twelve", ID))), "desk-query-invalid-cursor");
        assertRefused(() -> parse("termMonths", Map.of(), 10, codec.encode(first, List.of("12"))), "desk-query-invalid-cursor");
        assertRefused(() -> parse("termMonths", Map.of(), 10, codec.encode(first, List.of("12", "not-a-uuid"))),
                "desk-query-invalid-cursor");
    }

    @Test
    void everyGridsDefaultSortIsSortable() {
        for (GridDefinition grid : List.of(Grids.APPLICATIONS, Grids.ASSESSMENTS, Grids.LOANS)) {
            assertThat(grid.parse(null, Map.of(), null, null, 50, 200, codec).sort()).isNotEmpty();
            assertThat(grid.sortableNames()).isNotEmpty();
            assertThat(grid.filterNames()).isNotEmpty();
        }
    }
}
