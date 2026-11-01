package com.paypalseed.engines.rules.hardship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** TC-HSG-*: features and the scorecard, checked against 03 §13. */
class ScorecardTest {

    private static final HardshipPolicy P = Ana.POLICY;

    @Test
    void anasFeaturesMatchTheWorkedExample() {
        IncomeFeatures f = Ana.features();

        assertThat(f.baselineWeeklyCents()).isEqualTo(22_500);
        assertThat(f.baseline4Cents()).isEqualTo(90_000);
        assertThat(f.last4Cents()).isEqualTo(43_200);
        assertThat(f.dropBps()).isEqualTo(5_200);
        assertThat(f.recentMonthlyCents()).isEqualTo(Ana.RECENT_MONTHLY);
        assertThat(f.coverageBps()).isEqualTo(9_257);
        assertThat(f.weeksBelow()).isEqualTo(4);
        assertThat(f.persistence()).isEqualTo(IncomeFeatures.Persistence.PERSISTENT);
    }

    @Test
    void anaScoresSevenPointsLikelyMiss() {
        ScoreResult r = Scorecard.score(input(Optional.of(Ana.features()), 0, false, 0, false, false), P);

        assertThat(r.points()).isEqualTo(7);
        assertThat(r.level()).isEqualTo(RiskLevel.LIKELY_MISS);
        assertThat(r.signals()).extracting(Signal::code).containsExactly(Signal.INCOME_DROP_SEVERE, Signal.LOW_COVERAGE);
        assertThat(r.signals().get(0).evidence()).containsEntry("dropBps", 5_200L).containsEntry("last4Cents", 43_200L)
                .containsEntry("baseline4Cents", 90_000L);
        assertThat(r.signals().get(1).evidence()).containsEntry("coverageBps", 9_257L).containsEntry("instalmentCents", 15_000L);
        assertThat(SecondOpinion.borderline(r.points(), P)).isFalse();
    }

    @Test
    void ifNothingIsDoneAnasScoreClimbs() {
        Optional<IncomeFeatures> f = Optional.of(Ana.features());

        assertThat(Scorecard.score(input(f, 1, false, 0, false, false), P).points()).isEqualTo(10);
        ScoreResult inGrace = Scorecard.score(input(f, 1, false, 1, false, false), P);
        assertThat(inGrace.points()).isEqualTo(13);
        assertThat(inGrace.level()).isEqualTo(RiskLevel.IMMINENT);
    }

    @Test
    void onlyTheStrongerVariantOfAFamilyFires() {
        ScoreResult r = Scorecard.score(input(Optional.empty(), 2, true, 3, true, false), P);

        assertThat(r.signals()).extracting(Signal::code).containsExactly(Signal.AUTOPAY_FAILED_TWICE, Signal.IN_GRACE);
        assertThat(r.points()).isEqualTo(8);
    }

    @Test
    void recentLatenessCountsOnlyWhenNotLateNow() {
        ScoreResult r = Scorecard.score(input(Optional.empty(), 0, false, 0, true, false), P);

        assertThat(r.signals()).extracting(Signal::code).containsExactly(Signal.RECENT_LATE);
        assertThat(r.level()).isEqualTo(RiskLevel.NONE);
    }

    @Test
    void memberAskingForHelpIsImminentWhateverTheScore() {
        ScoreResult r = Scorecard.score(input(Optional.empty(), 0, false, 0, false, true), P);

        assertThat(r.level()).isEqualTo(RiskLevel.IMMINENT);
        assertThat(r.points()).isZero();
        assertThat(r.fired(Signal.MEMBER_REQUEST)).isTrue();
    }

    @Test
    void shortHistoryIsNoDataNotRisk() {
        long[] weeks = new long[13];
        Arrays.fill(weeks, 6, 13, 10_000);

        assertThat(FeatureExtractor.extract(weeks, Ana.INSTALMENT, P)).isEmpty();
        ScoreResult r = Scorecard.score(input(Optional.empty(), 0, false, 0, false, false), P);
        assertThat(r.level()).isEqualTo(RiskLevel.NONE);
        assertThat(r.signals()).isEmpty();
    }

    @Test
    void criticalCoverageReplacesLowCoverage() {
        long[] weeks = new long[13];
        Arrays.fill(weeks, 10_000);
        IncomeFeatures f = FeatureExtractor.extract(weeks, 60_000, P).orElseThrow();

        ScoreResult r = Scorecard.score(new ScorecardInput(Optional.of(f), 60_000, 0, false, false, 0, false, false, false), P);

        assertThat(f.coverageBps()).isLessThan(6_000);
        assertThat(r.signals()).extracting(Signal::code).containsExactly(Signal.CRITICAL_COVERAGE);
        assertThat(f.persistence()).isEqualTo(IncomeFeatures.Persistence.TRANSIENT);
    }

    @Test
    void rejectsMalformedSeries() {
        assertThatThrownBy(() -> FeatureExtractor.extract(new long[12], Ana.INSTALMENT, P))
                .isInstanceOf(IllegalArgumentException.class);
        long[] negative = Ana.WEEKS.clone();
        negative[3] = -1;
        assertThatThrownBy(() -> FeatureExtractor.extract(negative, Ana.INSTALMENT, P))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureExtractor.extract(Ana.WEEKS, 0, P)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyRejectsThresholdsThatDoNotRise() {
        HardshipPolicy.Scoring s = P.scoring();
        assertThatThrownBy(() -> new HardshipPolicy.Scoring(s.lookbackWeeks(), s.recentWeeks(), s.minHistoryWeeks(),
                s.scanLeadDays(), s.graceDays(), 6, 6, 9, s.incomeDropModerateBps(), s.incomeDropSevereBps(),
                s.usableShareBps(), s.coverageLowBps(), s.coverageCriticalBps(), s.persistenceBelowBps(),
                s.persistenceWeeks(), s.hysteresisScans(), s.failedChargeLookbackDays(), s.lateLookbackDays(),
                s.savingsStressLookbackDays(), s.points()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static ScorecardInput input(Optional<IncomeFeatures> f, int failed, boolean twice, int dpd, boolean late, boolean help) {
        return new ScorecardInput(f, Ana.INSTALMENT, failed, twice, false, dpd, late, false, help);
    }
}
