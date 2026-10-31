package com.paypalseed.engines.rules.hardship;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The worked example of 03-algorithms §13, shared by the tests. */
final class Ana {

    static final HardshipPolicy POLICY = HardshipPolicy.v1();
    static final LocalDate AS_OF = LocalDate.of(2027, 3, 10);
    static final UUID LOAN = UUID.fromString("0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e01");
    static final long INSTALMENT = 15_000;
    static final long[] WEEKS = {24_000, 21_500, 26_000, 23_000, 24_500, 22_500, 23_500, 25_000, 22_000, 11_500, 10_000, 11_200, 10_500};
    static final long RECENT_MONTHLY = 46_285;

    private Ana() {
    }

    static IncomeFeatures features() {
        return FeatureExtractor.extract(WEEKS, INSTALMENT, POLICY).orElseThrow();
    }

    /** Six owed $150 instalments, 2027-03-20 to 2027-08-20. */
    static List<OwedRow> owedBefore() {
        return monthly(LocalDate.of(2027, 3, 20), 6, 15_000);
    }

    static List<OwedRow> optionA() {
        List<OwedRow> rows = new ArrayList<>();
        rows.add(new OwedRow(LocalDate.of(2027, 4, 3), 15_000));
        rows.addAll(monthly(LocalDate.of(2027, 4, 20), 5, 15_000));
        return rows;
    }

    static List<OwedRow> optionB() {
        return monthly(LocalDate.of(2027, 4, 20), 6, 15_000);
    }

    /** REAMORTIZE(k): F4 spreads the remainder over the last instalments. */
    static List<OwedRow> optionC(int k) {
        int count = 6 + k;
        long base = 90_000 / count / 100 * 100;
        int extraDollars = (int) ((90_000 - base * count) / 100);
        List<OwedRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long amount = base + (i >= count - extraDollars ? 100 : 0);
            rows.add(new OwedRow(LocalDate.of(2027, 3, 20).plusMonths(i), amount));
        }
        return rows;
    }

    static List<OwedRow> monthly(LocalDate first, int count, long amount) {
        List<OwedRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new OwedRow(first.plusMonths(i), amount));
        }
        return rows;
    }

    static OptionMetrics metrics(List<OwedRow> after) {
        return OptionMetrics.compute(owedBefore(), after, AS_OF, RECENT_MONTHLY, POLICY);
    }
}
