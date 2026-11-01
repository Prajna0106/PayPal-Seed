package com.paypalseed.engines.rules.hardship;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** TC-AGT-05 to TC-AGT-08: the escalate-only rule of 03 §4. */
class SecondOpinionTest {

    private static final HardshipPolicy P = HardshipPolicy.v1();

    /** Five points: moderate drop (3) and low coverage (2), one under LIKELY_MISS. */
    private static ScoreResult fivePoints() {
        IncomeFeatures f = new IncomeFeatures(22_500, 90_000, 58_500, 3_500, 62_678, 9_000, 1,
                IncomeFeatures.Persistence.TRANSIENT);
        return Scorecard.score(new ScorecardInput(Optional.of(f), 15_000, 0, false, false, 0, false, false, false), P);
    }

    @Test
    void acceptsOneStepCitingFiredSignals() {
        ScoreResult r = fivePoints();
        assertThat(r.points()).isEqualTo(5);
        assertThat(r.level()).isEqualTo(RiskLevel.WATCH);

        SecondOpinion.Outcome o = SecondOpinion.evaluate(r, opinion(RiskLevel.LIKELY_MISS,
                Signal.INCOME_DROP_MODERATE, Signal.LOW_COVERAGE), true, P);

        assertThat(o).isInstanceOf(SecondOpinion.Accepted.class);
        SecondOpinion.Accepted a = (SecondOpinion.Accepted) o;
        assertThat(a.newLevel()).isEqualTo(RiskLevel.LIKELY_MISS);
        assertThat(a.signal().code()).isEqualTo(Signal.AI_ESCALATION);
        assertThat(a.signal().points()).isEqualTo(1);
    }

    @Test
    void neverLowersAndNeverJumpsTwoSteps() {
        assertThat(SecondOpinion.evaluate(fivePoints(), opinion(RiskLevel.NONE, Signal.LOW_COVERAGE), true, P))
                .isEqualTo(new SecondOpinion.Ignored("lower or equal: escalate-only"));
        assertThat(SecondOpinion.evaluate(fivePoints(), opinion(RiskLevel.WATCH, Signal.LOW_COVERAGE), true, P))
                .isEqualTo(new SecondOpinion.Ignored("lower or equal: escalate-only"));
        assertThat(SecondOpinion.evaluate(fivePoints(), opinion(RiskLevel.IMMINENT, Signal.LOW_COVERAGE, Signal.INCOME_DROP_MODERATE),
                true, P)).isEqualTo(new SecondOpinion.Ignored("more than one step"));
    }

    @Test
    void rejectsCitationsOfSignalsThatDidNotFire() {
        assertThat(SecondOpinion.evaluate(fivePoints(), opinion(RiskLevel.LIKELY_MISS, Signal.AUTOPAY_FAILED), true, P))
                .isEqualTo(new SecondOpinion.Rejected("cites a signal that did not fire"));
        assertThat(SecondOpinion.evaluate(fivePoints(), opinion(RiskLevel.LIKELY_MISS), true, P))
                .isEqualTo(new SecondOpinion.Rejected("cites a signal that did not fire"));
    }

    @Test
    void oneWeakSignalIsNotEnough() {
        assertThat(SecondOpinion.evaluate(fivePoints(), opinion(RiskLevel.LIKELY_MISS, Signal.LOW_COVERAGE), true, P))
                .isEqualTo(new SecondOpinion.Rejected("one weak signal is not enough"));
    }

    @Test
    void ignoredWhenNotBorderlineOrNoOutreach() {
        ScoreResult ana = Scorecard.score(new ScorecardInput(Optional.of(Ana.features()), Ana.INSTALMENT, 0, false, false, 0,
                false, false, false), P);
        assertThat(SecondOpinion.evaluate(ana, opinion(RiskLevel.IMMINENT, Signal.INCOME_DROP_SEVERE, Signal.LOW_COVERAGE), true, P))
                .isEqualTo(new SecondOpinion.Ignored("not borderline"));
        assertThat(SecondOpinion.evaluate(fivePoints(), opinion(RiskLevel.LIKELY_MISS, Signal.INCOME_DROP_MODERATE,
                Signal.LOW_COVERAGE), false, P)).isEqualTo(new SecondOpinion.Ignored("no outreach allowed"));
    }

    private static SecondOpinion.Opinion opinion(RiskLevel level, String... cited) {
        return new SecondOpinion.Opinion(level, List.of(cited), "INCOME_TREND");
    }
}
