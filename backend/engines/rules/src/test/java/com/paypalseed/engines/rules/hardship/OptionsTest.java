package com.paypalseed.engines.rules.hardship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** TC-HOP-*: option metrics, ranking, option D, caps and tiers against 03 §13. */
class OptionsTest {

    private static final HardshipPolicy P = Ana.POLICY;

    @Test
    void anasOptionMetricsMatchTheWorkedExample() {
        OptionMetrics a = Ana.metrics(Ana.optionA());
        OptionMetrics b = Ana.metrics(Ana.optionB());
        OptionMetrics c2 = Ana.metrics(Ana.optionC(2));

        assertThat(a).isEqualTo(new OptionMetrics(new BigDecimal("2.33"), 15_000, 3_240, false, 1));
        assertThat(b).isEqualTo(new OptionMetrics(new BigDecimal("30.67"), 15_000, 3_240, false, 1));
        assertThat(c2).isEqualTo(new OptionMetrics(new BigDecimal("30.90"), 11_300, 2_441, true, 8));
        assertThat(Ana.metrics(Ana.optionC(1)).meanDelayDays()).isEqualByComparingTo("15.56");
        assertThat(Ana.metrics(Ana.optionC(1)).sustainable()).isFalse();
        assertThat(Ana.metrics(Ana.optionC(6)).meanDelayDays()).isEqualByComparingTo("91.83");
    }

    @Test
    void anOptionMustKeepTheOwedTotal() {
        List<OwedRow> shorter = Ana.monthly(LocalDate.of(2027, 4, 20), 5, 15_000);
        assertThatThrownBy(() -> Ana.metrics(shorter)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owed total");
    }

    @Test
    void withoutIncomeDataThereIsNoBurdenAndNoSuggestion() {
        OptionMetrics m = OptionMetrics.compute(Ana.owedBefore(), Ana.optionB(), Ana.AS_OF, null, P);

        assertThat(m.burdenBps()).isNull();
        assertThat(m.sustainable()).isNull();
        assertThat(OptionRanker.recommend(anasCandidates(), Optional.empty())).isEmpty();
    }

    @Test
    void persistentDropSuggestsTheSmallestSustainableRestructure() {
        Optional<OptionCandidate> best = OptionRanker.recommendCandidate(anasCandidates(),
                Optional.of(IncomeFeatures.Persistence.PERSISTENT));

        assertThat(best).get().extracting(OptionCandidate::kind).isEqualTo(OptionKind.C_RESTRUCTURE);
        assertThat(best.get().arg(OptionCandidate.EXTRA_MONTHS)).isEqualTo(2);
        assertThat(OptionRanker.restructureVariant(anasCandidates())).get()
                .extracting(c -> c.arg(OptionCandidate.EXTRA_MONTHS)).isEqualTo(2);
    }

    @Test
    void persistentDropWithNoSustainablePlanSuggestsTheLongestOne() {
        List<OptionCandidate> tight = List.of(
                candidate(OptionKind.C_RESTRUCTURE, Map.of("extraMonths", 1), Ana.metrics(Ana.optionC(1)), true),
                candidate(OptionKind.C_RESTRUCTURE, Map.of("extraMonths", 3), unsustainable(Ana.metrics(Ana.optionC(3))), true));

        assertThat(OptionRanker.recommendCandidate(tight, Optional.of(IncomeFeatures.Persistence.PERSISTENT))).get()
                .extracting(c -> c.arg(OptionCandidate.EXTRA_MONTHS)).isEqualTo(3);
    }

    @Test
    void transientDropSuggestsTheLeastChangeAllowed() {
        Optional<IncomeFeatures.Persistence> transientDrop = Optional.of(IncomeFeatures.Persistence.TRANSIENT);
        assertThat(OptionRanker.recommend(anasCandidates(), transientDrop)).contains(OptionKind.A_MOVE_DATE);

        List<OptionCandidate> aCapped = anasCandidates().stream()
                .map(c -> c.kind() == OptionKind.A_MOVE_DATE ? candidate(c.kind(), c.args(), c.metrics(), false) : c)
                .toList();
        assertThat(OptionRanker.recommend(aCapped, transientDrop)).contains(OptionKind.B_PAUSE);
    }

    @Test
    void paymentIsSuggestedOnlyWhenItCoversTheInstalment() {
        OptionMetrics none = new OptionMetrics(BigDecimal.ZERO.setScale(2), 15_000, 3_240, false, 0);
        List<OptionCandidate> withCoveringD = List.of(
                candidate(OptionKind.D_PAYMENT, Map.of("amountCents", 15_000, OptionCandidate.COVERS_INSTALMENT, 1), none, true),
                candidate(OptionKind.A_MOVE_DATE, Map.of("seq", 3, "days", 14), Ana.metrics(Ana.optionA()), true));

        assertThat(OptionRanker.recommend(withCoveringD, Optional.of(IncomeFeatures.Persistence.TRANSIENT)))
                .contains(OptionKind.D_PAYMENT);
        assertThat(OptionRanker.recommend(anasCandidates(), Optional.of(IncomeFeatures.Persistence.TRANSIENT)))
                .isNotEqualTo(Optional.of(OptionKind.D_PAYMENT));
    }

    @Test
    void paymentOptionAmounts() {
        assertThat(PaymentOption.amountCents(Ana.RECENT_MONTHLY, Ana.INSTALMENT, P)).isEqualTo(OptionalLong.of(6_900));
        assertThat(PaymentOption.amountCents(null, Ana.INSTALMENT, P)).isEqualTo(OptionalLong.of(7_500));
        assertThat(PaymentOption.amountCents(500L, Ana.INSTALMENT, P)).isEmpty();
        assertThat(PaymentOption.amountCents(10_000_000L, Ana.INSTALMENT, P)).isEmpty();
    }

    @Test
    void capsCountOnlyAppliedOptions() {
        assertThat(HardshipCaps.violation(OptionKind.A_MOVE_DATE, HardshipCaps.History.NONE, P)).isEmpty();
        assertThat(HardshipCaps.violation(OptionKind.A_MOVE_DATE, new HardshipCaps.History(2, 0, 0, 0), P)).isPresent();
        assertThat(HardshipCaps.violation(OptionKind.B_PAUSE, new HardshipCaps.History(0, 1, 1, 0), P)).isPresent();
        assertThat(HardshipCaps.violation(OptionKind.B_PAUSE, new HardshipCaps.History(0, 0, 2, 0), P)).isPresent();
        assertThat(HardshipCaps.violation(OptionKind.B_PAUSE, new HardshipCaps.History(0, 0, 1, 0), P)).isEmpty();
        assertThat(HardshipCaps.violation(OptionKind.C_RESTRUCTURE, new HardshipCaps.History(0, 0, 0, 1), P)).get()
                .asString().contains("restructure");
        assertThat(HardshipCaps.violation(OptionKind.D_PAYMENT, new HardshipCaps.History(9, 9, 9, 9), P)).isEmpty();
    }

    @Test
    void tiers() {
        assertThat(ApprovalTier.forKind(OptionKind.A_MOVE_DATE)).isEqualTo(ApprovalTier.AUTO_POLICY);
        assertThat(ApprovalTier.forKind(OptionKind.B_PAUSE)).isEqualTo(ApprovalTier.MAKER_CHECKER);
        assertThat(ApprovalTier.forKind(OptionKind.C_RESTRUCTURE)).isEqualTo(ApprovalTier.MAKER_CHECKER);
        assertThat(ApprovalTier.forKind(OptionKind.D_PAYMENT)).isEqualTo(ApprovalTier.NONE);
        assertThat(OptionKind.fromLabel("B")).isEqualTo(OptionKind.B_PAUSE);
        assertThat(OptionKind.B_PAUSE.primitive()).isEqualTo("DEFER_TO_END");
    }

    static List<OptionCandidate> anasCandidates() {
        return List.of(
                candidate(OptionKind.A_MOVE_DATE, Map.of("seq", 3, "days", 14), Ana.metrics(Ana.optionA()), true),
                candidate(OptionKind.B_PAUSE, Map.of("seq", 3), Ana.metrics(Ana.optionB()), true),
                candidate(OptionKind.C_RESTRUCTURE, Map.of("extraMonths", 1), Ana.metrics(Ana.optionC(1)), true),
                candidate(OptionKind.C_RESTRUCTURE, Map.of("extraMonths", 2), Ana.metrics(Ana.optionC(2)), true),
                candidate(OptionKind.C_RESTRUCTURE, Map.of("extraMonths", 6), Ana.metrics(Ana.optionC(6)), true),
                candidate(OptionKind.D_PAYMENT, Map.of("amountCents", 6_900),
                        new OptionMetrics(BigDecimal.ZERO.setScale(2), 8_100, 1_750, true, 1), true));
    }

    private static OptionCandidate candidate(OptionKind kind, Map<String, Integer> args, OptionMetrics m, boolean ok) {
        return new OptionCandidate(kind, args, m, ok);
    }

    private static OptionMetrics unsustainable(OptionMetrics m) {
        return new OptionMetrics(m.meanDelayDays(), m.maxInstalmentCents(), 2_600, false, m.instalmentsMoved());
    }
}
