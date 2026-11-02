package com.paypalseed.engines.rules.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.engines.rules.LoanTier;
import org.junit.jupiter.api.Test;

class RecordLevelsTest {

    private static RecordFacts facts(int months, int missed, int loans, int onTime, int late, int open) {
        return new RecordFacts(months, missed, loans, 0, onTime, late, open);
    }

    @Test
    void anEmptyRecordIsNew() {
        assertThat(RecordLevels.evaluate(RecordFacts.EMPTY)).isEqualTo(RecordLevel.NEW);
        assertThat(RecordFacts.EMPTY.onTimeBps()).isEqualTo(10_000);
    }

    @Test
    void thresholdsAreExactAtTheBoundary() {
        assertThat(RecordLevels.evaluate(facts(2, 0, 0, 0, 0, 0))).isEqualTo(RecordLevel.NEW);
        assertThat(RecordLevels.evaluate(facts(3, 0, 0, 0, 0, 0))).isEqualTo(RecordLevel.BUILDING);
        assertThat(RecordLevels.evaluate(facts(6, 0, 0, 0, 0, 0))).isEqualTo(RecordLevel.BUILDING);
        assertThat(RecordLevels.evaluate(facts(6, 0, 1, 3, 0, 0))).isEqualTo(RecordLevel.STEADY);
        assertThat(RecordLevels.evaluate(facts(9, 0, 2, 6, 0, 0))).isEqualTo(RecordLevel.TRUSTED);
        assertThat(RecordLevels.evaluate(facts(12, 0, 3, 9, 0, 0))).isEqualTo(RecordLevel.EXEMPLARY);
        assertThat(RecordLevels.evaluate(facts(30, 0, 2, 9, 0, 0))).isEqualTo(RecordLevel.TRUSTED);
    }

    @Test
    void theOnTimeShareGatesEachLevel() {
        // 3 on-time months and 1 missed month is 75%: exactly the BUILDING floor.
        assertThat(RecordLevels.evaluate(facts(3, 1, 0, 0, 0, 0))).isEqualTo(RecordLevel.BUILDING);
        // 3 on time and 2 missed is 60%: below the floor.
        assertThat(RecordLevels.evaluate(facts(3, 2, 0, 0, 0, 0))).isEqualTo(RecordLevel.NEW);
        // 12 months, 3 loans and 94% is TRUSTED at best: EXEMPLARY needs 95%.
        assertThat(RecordFacts.EMPTY.plusSavingMonthOnTime().onTimeBps()).isEqualTo(10_000);
        RecordFacts almost = new RecordFacts(12, 1, 3, 0, 36, 1, 0);
        assertThat(almost.onTimeBps()).isEqualTo(9_600);
        assertThat(RecordLevels.evaluate(almost)).isEqualTo(RecordLevel.EXEMPLARY);
        RecordFacts lower = new RecordFacts(12, 2, 3, 0, 36, 2, 0);
        assertThat(lower.onTimeBps()).isEqualTo(9_230);
        assertThat(RecordLevels.evaluate(lower)).isEqualTo(RecordLevel.TRUSTED);
    }

    @Test
    void anUnresolvedLatePaymentBlocksPromotion() {
        assertThat(RecordLevels.evaluate(facts(12, 0, 3, 9, 0, 1))).isEqualTo(RecordLevel.NEW);
        RecordLevels.NextStep step = RecordLevels.nextStep(RecordLevel.NEW, facts(12, 0, 3, 9, 0, 1)).orElseThrow();
        assertThat(step.latePaymentOpen()).isTrue();
    }

    @Test
    void aLevelIsHeldWhenTheHistoryLaterLooksWorse() {
        RecordFacts worse = facts(3, 5, 0, 0, 3, 2);
        assertThat(RecordLevels.evaluate(worse)).isEqualTo(RecordLevel.NEW);
        assertThat(RecordLevels.next(RecordLevel.STEADY, worse)).isEqualTo(RecordLevel.STEADY);
    }

    @Test
    void onlyADefaultLowersALevel() {
        assertThat(RecordLevels.afterDefault()).isEqualTo(RecordLevel.NEW);
    }

    @Test
    void graceBoundary() {
        assertThat(RecordLevels.paidOnTime(0)).isTrue();
        assertThat(RecordLevels.paidOnTime(5)).isTrue();
        assertThat(RecordLevels.paidOnTime(6)).isFalse();
    }

    @Test
    void nextStepNamesWhatIsLeft() {
        RecordLevels.NextStep step = RecordLevels.nextStep(RecordLevel.BUILDING, facts(4, 0, 0, 2, 0, 0)).orElseThrow();
        assertThat(step.target()).isEqualTo(RecordLevel.STEADY);
        assertThat(step.onTimeSavingMonthsToGo()).isEqualTo(2);
        assertThat(step.loansRepaidOnPlanToGo()).isEqualTo(1);
        assertThat(step.requiredOnTimeBps()).isEqualTo(8_500);
        assertThat(step.onTimeShareTooLow()).isFalse();
        assertThat(RecordLevels.nextStep(RecordLevel.EXEMPLARY, RecordFacts.EMPTY)).isEmpty();
    }

    @Test
    void limitsLineUpWithTheMicroloanLadder() {
        assertThat(RecordLevel.BUILDING.loanLimitCents()).isEqualTo(LoanTier.LEVEL_1.maxLoanCents());
        assertThat(RecordLevel.STEADY.loanLimitCents()).isEqualTo(LoanTier.LEVEL_2.maxLoanCents());
        assertThat(RecordLevel.TRUSTED.loanLimitCents()).isEqualTo(LoanTier.LEVEL_3.maxLoanCents());
        assertThat(RecordLevel.EXEMPLARY.loanLimitCents()).isGreaterThan(LoanTier.LEVEL_3.maxLoanCents());
        assertThat(RecordLevel.NEW.ladderTier()).isEqualTo(LoanTier.UNQUALIFIED);
        assertThat(RecordLevel.EXEMPLARY.ladderTier()).isEqualTo(LoanTier.LEVEL_3);
        assertThat(RecordLimits.limitCents(RecordLevel.NEW, 90_000)).isEqualTo(90_000);
        assertThat(RecordLimits.limitCents(RecordLevel.STEADY, 150_000)).isEqualTo(300_000);
        assertThat(RecordLimits.limitCents(RecordLevel.STEADY, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(RecordLimits.limitCents(RecordLevel.NEW, Long.MIN_VALUE)).isZero();
    }

    @Test
    void bandsHaveStableEdges() {
        assertThat(OnTimeBand.of(10_000)).isEqualTo(OnTimeBand.EXCELLENT);
        assertThat(OnTimeBand.of(9_500)).isEqualTo(OnTimeBand.EXCELLENT);
        assertThat(OnTimeBand.of(9_499)).isEqualTo(OnTimeBand.STRONG);
        assertThat(OnTimeBand.of(8_500)).isEqualTo(OnTimeBand.STRONG);
        assertThat(OnTimeBand.of(7_000)).isEqualTo(OnTimeBand.FAIR);
        assertThat(OnTimeBand.of(6_999)).isEqualTo(OnTimeBand.DEVELOPING);
        assertThat(OnTimeBand.of(0)).isEqualTo(OnTimeBand.DEVELOPING);
    }

    @Test
    void negativeCountsAreRefused() {
        assertThatThrownBy(() -> new RecordFacts(-1, 0, 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordLevel.ofRank(9)).isInstanceOf(IllegalArgumentException.class);
    }
}
