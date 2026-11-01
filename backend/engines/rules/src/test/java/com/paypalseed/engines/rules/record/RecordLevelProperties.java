package com.paypalseed.engines.rules.record;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** Monotonicity and bounds of the F6 level policy. */
class RecordLevelProperties {

    @Provide
    Arbitrary<RecordFacts> facts() {
        Arbitrary<Integer> small = Arbitraries.integers().between(0, 40);
        return Combinators.combine(small, small, small, small, small, small, Arbitraries.integers().between(0, 3))
                .as(RecordFacts::new);
    }

    @Provide
    Arbitrary<RecordLevel> levels() {
        return Arbitraries.of(RecordLevel.values());
    }

    /** More good history, with the bad counts no higher, never lowers the evaluated level. */
    @Property(tries = 3000)
    void moreGoodHistoryNeverLowersTheLevel(@ForAll("facts") RecordFacts base, @ForAll @IntRange(max = 12) int moreMonths,
                                            @ForAll @IntRange(max = 3) int moreLoans, @ForAll @IntRange(max = 30) int moreOnTime,
                                            @ForAll @IntRange(max = 40) int fewerMissed, @ForAll @IntRange(max = 40) int fewerLate,
                                            @ForAll @IntRange(max = 3) int fewerOpen) {
        RecordFacts better = new RecordFacts(base.onTimeSavingMonths() + moreMonths,
                Math.max(0, base.missedSavingMonths() - fewerMissed), base.loansRepaidOnPlan() + moreLoans,
                base.loansRepaidOffPlan(), base.instalmentsOnTime() + moreOnTime,
                Math.max(0, base.instalmentsLate() - fewerLate), Math.max(0, base.openLateInstalments() - fewerOpen));

        assertThat(RecordLevels.evaluate(better).rank()).isGreaterThanOrEqualTo(RecordLevels.evaluate(base).rank());
    }

    /** The level carried forward never falls, whatever the facts say. */
    @Property(tries = 3000)
    void nextNeverLowersALevel(@ForAll("levels") RecordLevel current, @ForAll("facts") RecordFacts facts) {
        assertThat(RecordLevels.next(current, facts).rank()).isGreaterThanOrEqualTo(current.rank());
    }

    /** Folding any sequence of fact snapshots never produces a lower level than an earlier step. */
    @Property(tries = 1000)
    void aSequenceOfEvaluationsIsNonDecreasing(@ForAll("facts") RecordFacts a, @ForAll("facts") RecordFacts b,
                                               @ForAll("facts") RecordFacts c) {
        RecordLevel first = RecordLevels.next(RecordLevel.NEW, a);
        RecordLevel second = RecordLevels.next(first, b);
        RecordLevel third = RecordLevels.next(second, c);
        assertThat(first.rank()).isLessThanOrEqualTo(second.rank());
        assertThat(second.rank()).isLessThanOrEqualTo(third.rank());
    }

    /** One late payment inside grace is an on-time payment: it cannot change the level. */
    @Property(tries = 1000)
    void aPaymentInsideGraceCountsAsOnTime(@ForAll @IntRange(min = 0, max = RecordLevels.GRACE_DAYS) int daysLate) {
        assertThat(RecordLevels.paidOnTime(daysLate)).isTrue();
        assertThat(RecordLevels.paidOnTime(RecordLevels.GRACE_DAYS + 1 + daysLate)).isFalse();
    }

    /** An unresolved late payment blocks promotion but a level already held stays. */
    @Property(tries = 1000)
    void anOpenLatePaymentBlocksPromotionButNeverDemotes(@ForAll("levels") RecordLevel current,
                                                         @ForAll("facts") RecordFacts facts) {
        RecordFacts open = facts.withOpenLate(Math.max(1, facts.openLateInstalments()));
        assertThat(RecordLevels.evaluate(open)).isEqualTo(RecordLevel.NEW);
        assertThat(RecordLevels.next(current, open)).isEqualTo(current);
    }

    @Property(tries = 3000)
    void onTimeShareStaysBetweenZeroAndFull(@ForAll @IntRange(max = Integer.MAX_VALUE) int a, @ForAll @IntRange(max = Integer.MAX_VALUE) int b,
                                            @ForAll @IntRange(max = Integer.MAX_VALUE) int c, @ForAll @IntRange(max = Integer.MAX_VALUE) int d,
                                            @ForAll @IntRange(max = Integer.MAX_VALUE) int e) {
        RecordFacts f = new RecordFacts(a, b, 0, 0, c, d, e);
        assertThat(f.onTimeBps()).isBetween(0, RecordFacts.FULL_BPS);
    }

    @Property(tries = 2000)
    void aBetterShareNeverLandsInALowerBand(@ForAll @IntRange(min = 0, max = 10_000) int lower,
                                            @ForAll @IntRange(min = 0, max = 10_000) int extra) {
        int higher = Math.min(10_000, lower + extra);
        assertThat(OnTimeBand.of(higher).ordinal()).isGreaterThanOrEqualTo(OnTimeBand.of(lower).ordinal());
    }

    /** No base limit, however extreme, gives a negative or overflowing ceiling, and the record only raises it. */
    @Property(tries = 5000)
    void limitsAreNeverNegativeAndNeverShrink(@ForAll("levels") RecordLevel level, @ForAll long base) {
        long limit = RecordLimits.limitCents(level, base);
        assertThat(limit).isGreaterThanOrEqualTo(0L);
        assertThat(limit).isGreaterThanOrEqualTo(level.loanLimitCents());
        assertThat(limit).isGreaterThanOrEqualTo(Math.max(0L, base));
    }

    @Property(tries = 500)
    void higherLevelsUnlockLargerLimits(@ForAll("levels") RecordLevel a, @ForAll("levels") RecordLevel b, @ForAll long base) {
        if (a.atLeast(b)) {
            assertThat(RecordLimits.limitCents(a, base)).isGreaterThanOrEqualTo(RecordLimits.limitCents(b, base));
        }
    }

    @Property(tries = 200)
    void countersSaturateInsteadOfOverflowing(@ForAll @IntRange(min = Integer.MAX_VALUE - 1, max = Integer.MAX_VALUE) int big) {
        RecordFacts f = new RecordFacts(big, big, big, big, big, big, big);
        RecordFacts g = f.plusSavingMonthOnTime().plusSavingMonthsMissed(5).plusInstalmentOnTime().plusInstalmentLate()
                .plusLoanRepaid(true).plusLoanRepaid(false);
        assertThat(g.onTimeSavingMonths()).isEqualTo(Integer.MAX_VALUE);
        assertThat(g.onTimeBps()).isBetween(0, RecordFacts.FULL_BPS);
    }
}
