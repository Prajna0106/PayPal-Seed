package com.paypalseed.engines.rules.hardship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** SLA clock (03 §10), rubber-stamp detector (03 §12) and snapshot hash (03 §8). */
class TimingAndAuditTest {

    private static final HardshipPolicy P = HardshipPolicy.v1();
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    @Test
    void anasDecisionIsDueFridayMorning() {
        Instant start = la(2027, 3, 11, 9, 42);

        assertThat(SlaClock.due(start, false, P)).isEqualTo(la(2027, 3, 12, 9, 42));
        assertThat(SlaClock.at(start, false, 50, P)).isEqualTo(la(2027, 3, 11, 14, 42));
        assertThat(SlaClock.at(start, false, 80, P)).isEqualTo(la(2027, 3, 11, 17, 42));
    }

    @Test
    void slaSkipsNightsAndWeekendsAcrossDaylightSaving() {
        assertThat(SlaClock.due(la(2027, 3, 12, 16, 0), false, P)).isEqualTo(la(2027, 3, 15, 16, 0));
        assertThat(SlaClock.due(la(2027, 3, 13, 11, 0), false, P)).isEqualTo(la(2027, 3, 15, 18, 0));
        assertThat(SlaClock.due(la(2027, 3, 11, 6, 0), true, P)).isEqualTo(la(2027, 3, 11, 12, 0));
        assertThat(SlaClock.due(la(2027, 3, 11, 17, 0), true, P)).isEqualTo(la(2027, 3, 12, 11, 0));
        assertThatThrownBy(() -> SlaClock.at(la(2027, 3, 11, 9, 0), false, 0, P)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rubberStampNeedsAWindowOfFastApprovals() {
        List<RubberStampDetector.Decision> fast = new ArrayList<>(Collections.nCopies(20,
                new RubberStampDetector.Decision(true, 4_000, 2_000)));
        assertThat(RubberStampDetector.flag(fast)).isTrue();
        assertThat(RubberStampDetector.flag(fast.subList(0, 19))).isFalse();

        List<RubberStampDetector.Decision> careful = new ArrayList<>(Collections.nCopies(11,
                new RubberStampDetector.Decision(true, 40_000, 35_000)));
        careful.addAll(Collections.nCopies(9, new RubberStampDetector.Decision(true, 4_000, 2_000)));
        assertThat(RubberStampDetector.flag(careful)).isFalse();

        List<RubberStampDetector.Decision> mixed = new ArrayList<>(fast);
        for (int i = 0; i < 3; i++) {
            mixed.set(i, new RubberStampDetector.Decision(false, 4_000, 2_000));
        }
        assertThat(RubberStampDetector.flag(mixed)).isFalse();
    }

    @Test
    void snapshotHashIsStableUnderArgumentOrderAndSensitiveToRows() {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        ordered.put("seq", 3);
        ordered.put("days", 14);
        Map<String, Integer> sorted = new TreeMap<>(ordered);
        List<SnapshotRow> rows = List.of(new SnapshotRow(3, LocalDate.of(2027, 4, 3), 15_000, 15_000),
                new SnapshotRow(4, LocalDate.of(2027, 4, 20), 15_000, 15_000));

        String h = SnapshotHash.of(Ana.LOAN, 1, "MOVE_DATE", ordered, rows);

        assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(SnapshotHash.of(Ana.LOAN, 1, "MOVE_DATE", sorted, rows)).isEqualTo(h);
        assertThat(SnapshotHash.of(Ana.LOAN, 1, "MOVE_DATE", sorted,
                List.of(new SnapshotRow(3, LocalDate.of(2027, 4, 3), 15_000, 10_000), rows.get(1)))).isNotEqualTo(h);
        assertThat(SnapshotHash.of(Ana.LOAN, 2, "MOVE_DATE", sorted, rows)).isNotEqualTo(h);
        assertThat(SnapshotHash.of(Ana.LOAN, 1, "DEFER_TO_END", sorted, rows)).isNotEqualTo(h);
    }

    private static Instant la(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(LA).toInstant();
    }
}
