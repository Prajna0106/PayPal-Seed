package com.paypalseed.deskquery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.DomainException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    private static final String ID = "0197f3a4-1b2c-7d3e-8f40-5a6b7c8d9e10";
    private final CursorCodec codec = new CursorCodec("unit-test-key-0123456789abcdef");

    private static GridQuery query(String grid, boolean desc, Map<String, List<String>> filters) {
        return new GridQuery(grid, List.of(new SortKey("submittedAt", desc)), filters, 50, null);
    }

    @Test
    void roundTripsTheLastRowsKeys() {
        GridQuery q = query("applications", true, Map.of("status", List.of("SUBMITTED")));
        List<String> keys = List.of("2026-11-01T10:15:30.123456Z", ID);

        String cursor = codec.encode(q, keys);

        assertThat(codec.decode(cursor, q)).isEqualTo(keys);
    }

    @Test
    void isOpaqueAndUrlSafe() {
        String cursor = codec.encode(query("applications", true, Map.of()), List.of("2026-11-01T10:15:30Z", ID));

        assertThat(cursor).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(cursor).doesNotContain("submittedAt").doesNotContain(ID);
    }

    @Test
    void isBoundToTheSort() {
        String cursor = codec.encode(query("applications", true, Map.of()), List.of("2026-11-01T10:15:30Z", ID));

        assertRefused(() -> codec.decode(cursor, query("applications", false, Map.of())));
    }

    @Test
    void isBoundToTheFilters() {
        String cursor = codec.encode(query("applications", true, Map.of("status", List.of("SUBMITTED"))),
                List.of("2026-11-01T10:15:30Z", ID));

        assertRefused(() -> codec.decode(cursor, query("applications", true, Map.of("status", List.of("APPROVED")))));
        assertRefused(() -> codec.decode(cursor, query("applications", true, Map.of())));
    }

    @Test
    void isBoundToTheList() {
        String cursor = codec.encode(query("applications", true, Map.of()), List.of("2026-11-01T10:15:30Z", ID));

        assertRefused(() -> codec.decode(cursor, query("assessments", true, Map.of())));
    }

    @Test
    void refusesATamperedBody() {
        GridQuery q = query("applications", true, Map.of());
        String cursor = codec.encode(q, List.of("2026-11-01T10:15:30Z", ID));
        String[] parts = cursor.split("\\.");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"v\":1,\"k\":[\"1999-01-01T00:00:00Z\",\"" + UUID.randomUUID() + "\"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertRefused(() -> codec.decode(forged + "." + parts[1], q));
    }

    @Test
    void refusesATamperedSignature() {
        GridQuery q = query("applications", true, Map.of());
        String cursor = codec.encode(q, List.of("2026-11-01T10:15:30Z", ID));
        char last = cursor.charAt(cursor.length() - 1);
        String flipped = cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertRefused(() -> codec.decode(flipped, q));
    }

    @Test
    void refusesACursorFromAnotherKey() {
        GridQuery q = query("applications", true, Map.of());
        String cursor = new CursorCodec("another-key-another-key-another-key").encode(q, List.of("2026-11-01T10:15:30Z", ID));

        assertRefused(() -> codec.decode(cursor, q));
    }

    @Test
    void refusesGarbage() {
        GridQuery q = query("applications", true, Map.of());
        for (String junk : List.of("", ".", "abc", "abc.", ".abc", "a.b.c", "not base64 !!.sig", "' OR 1=1 --", "eyJ2IjoxfQ.eyJ2IjoxfQ")) {
            assertRefused(() -> codec.decode(junk, q));
        }
    }

    @Test
    void aBlankKeyGivesAPerProcessKeyThatStillWorksWithinTheProcess() {
        CursorCodec random = new CursorCodec("");
        GridQuery q = query("applications", true, Map.of());

        assertThat(random.decode(random.encode(q, List.of("a", ID)), q)).containsExactly("a", ID);
        assertRefused(() -> new CursorCodec("").decode(random.encode(q, List.of("a", ID)), q));
    }

    private static void assertRefused(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo("desk-query-invalid-cursor"));
    }
}
