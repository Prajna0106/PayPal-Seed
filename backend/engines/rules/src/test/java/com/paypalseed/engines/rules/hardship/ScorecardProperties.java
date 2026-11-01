package com.paypalseed.engines.rules.hardship;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/** PROP-H-01 to PROP-H-04: monotonic, deterministic, hysteresis, escalate-only. */
class ScorecardProperties {

    private static final HardshipPolicy P = HardshipPolicy.v1();

    /** PROP-H-01: more evidence of trouble never lowers the points or the level. */
    @Property(tries = 2000)
    void moreTroubleNeverLowersTheScore(@ForAll @IntRange(max = 9_000) int drop, @ForAll @IntRange(max = 1_000) int moreDrop,
                                        @ForAll @IntRange(min = 1_000, max = 20_000) int coverage,
                                        @ForAll @IntRange(max = 1_000) int lessCoverage,
                                        @ForAll @IntRange(max = 3) int failed, @ForAll @IntRange(max = 2) int moreFailed,
                                        @ForAll @IntRange(max = 10) int dpd, @ForAll @IntRange(max = 5) int moreDpd,
                                        @ForAll boolean twice, @ForAll boolean slip, @ForAll boolean late, @ForAll boolean stress,
                                        @ForAll boolean hasIncome) {
        ScorecardInput base = new ScorecardInput(features(hasIncome, drop, coverage), 15_000, failed, twice && failed > 0, slip,
                dpd, late, stress, false);
        ScorecardInput worse = new ScorecardInput(features(hasIncome, drop + moreDrop, coverage - lessCoverage), 15_000,
                failed + moreFailed, twice && failed > 0, slip, dpd + moreDpd, late, stress, false);

        ScoreResult a = Scorecard.score(base, P);
        ScoreResult b = Scorecard.score(worse, P);

        assertThat(b.points()).isGreaterThanOrEqualTo(a.points());
        assertThat(b.level().atLeast(a.level())).isTrue();
    }

    /** PROP-H-02: the same input always gives the same result. */
    @Property(tries = 500)
    void scoringIsDeterministic(@ForAll @Size(13) List<@LongRange(min = 0, max = 200_000) Long> weekList,
                                @ForAll @LongRange(min = 100, max = 100_000) long instalment, @ForAll @IntRange(max = 3) int failed) {
        long[] weeks = weekList.stream().mapToLong(Long::longValue).toArray();
        Optional<IncomeFeatures> f = FeatureExtractor.extract(weeks, instalment, P);
        ScorecardInput in = new ScorecardInput(f, instalment, failed, false, false, 0, false, false, false);

        assertThat(Scorecard.score(in, P)).isEqualTo(Scorecard.score(in, P));
        assertThat(FeatureExtractor.extract(weeks, instalment, P)).isEqualTo(f);
    }

    /** PROP-H-03: a level rises at once and falls only after two consecutive lower scans. */
    @Property(tries = 500)
    void hysteresisFallsOnlyAfterTwoLowerScans(@ForAll RiskLevel previous, @ForAll RiskLevel raw) {
        Hysteresis.State first = Hysteresis.apply(raw, previous, 0, P);
        if (raw.atLeast(previous)) {
            assertThat(first).isEqualTo(new Hysteresis.State(raw, 0));
            return;
        }
        assertThat(first).isEqualTo(new Hysteresis.State(previous, 1));
        assertThat(Hysteresis.apply(raw, first.level(), first.belowCount(), P)).isEqualTo(new Hysteresis.State(raw, 0));
    }

    /** PROP-H-04: an accepted second opinion raises the level by exactly one step and never lowers it. */
    @Property(tries = 1000)
    void secondOpinionIsEscalateOnly(@ForAll @IntRange(max = 20) int drop, @ForAll RiskLevel suggested,
                                     @ForAll boolean failed, @ForAll boolean late, @ForAll boolean outreach) {
        ScorecardInput in = new ScorecardInput(features(true, drop * 500, 20_000), 15_000, failed ? 1 : 0, false, false, 0,
                late, false, false);
        ScoreResult r = Scorecard.score(in, P);
        SecondOpinion.Outcome o = SecondOpinion.evaluate(r,
                new SecondOpinion.Opinion(suggested, r.signals().stream().map(Signal::code).toList(), "INCOME_TREND"), outreach, P);

        if (o instanceof SecondOpinion.Accepted a) {
            assertThat(a.newLevel()).isEqualTo(r.level().next());
            assertThat(a.newLevel()).isNotEqualTo(r.level());
            assertThat(r.points() + a.signal().points()).isEqualTo(a.newLevel().threshold(P.scoring()));
        }
    }

    private static Optional<IncomeFeatures> features(boolean present, int dropBps, int coverageBps) {
        if (!present) {
            return Optional.empty();
        }
        int drop = Math.min(10_000, Math.max(0, dropBps));
        return Optional.of(new IncomeFeatures(22_500, 90_000, 90_000L * (10_000 - drop) / 10_000, drop, 50_000,
                Math.max(0, coverageBps), 0, IncomeFeatures.Persistence.TRANSIENT));
    }
}
