package com.paypalseed.deskquery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.deskquery.domain.model.ImpactFigures.Raw;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ImpactFiguresTest {

    @Test
    void anEmptyPortfolioHasZeroesAndNoRate() {
        ImpactFigures f = ImpactFigures.of(new Raw(0, 0, 0, 0, 0, 0));

        assertThat(f.membersServed()).isZero();
        assertThat(f.averageLoanSizeCents()).isZero();
        assertThat(f.firstLoanOnPlanRateBasisPoints()).isNull();
    }

    @Test
    void everyBorrowerWasLoanedToWithoutACreditScore() {
        ImpactFigures f = ImpactFigures.of(new Raw(40, 12, 14, 1_400_000, 0, 0));

        assertThat(f.membersWithLoan()).isEqualTo(12);
        assertThat(f.membersLoanedWithoutCreditScore()).isEqualTo(f.membersWithLoan());
        assertThat(f.membersServed()).isEqualTo(40);
    }

    @Test
    void averageLoanSizeRoundsHalfUp() {
        assertThat(ImpactFigures.of(new Raw(3, 3, 3, 300_000, 0, 0)).averageLoanSizeCents()).isEqualTo(100_000);
        assertThat(ImpactFigures.of(new Raw(2, 2, 2, 100_001, 0, 0)).averageLoanSizeCents()).isEqualTo(50_001);
        assertThat(ImpactFigures.of(new Raw(2, 2, 2, 100_000, 0, 0)).averageLoanSizeCents()).isEqualTo(50_000);
        assertThat(ImpactFigures.of(new Raw(3, 3, 3, 100_001, 0, 0)).averageLoanSizeCents()).isEqualTo(33_334);
    }

    @Test
    void firstLoanRepaidOnPlanRateIsShareOfFinishedFirstLoans() {
        ImpactFigures f = ImpactFigures.of(new Raw(10, 10, 10, 1_000_000, 8, 6));

        assertThat(f.firstLoansRepaid()).isEqualTo(8);
        assertThat(f.firstLoansRepaidOnPlan()).isEqualTo(6);
        assertThat(f.firstLoanOnPlanRateBasisPoints()).isEqualTo(7_500);
    }

    @Test
    void theRateRoundsToTheNearestBasisPointAndNeverExceedsAll() {
        assertThat(ImpactFigures.of(new Raw(3, 3, 3, 3, 3, 1)).firstLoanOnPlanRateBasisPoints()).isEqualTo(3_333);
        assertThat(ImpactFigures.of(new Raw(3, 3, 3, 3, 3, 2)).firstLoanOnPlanRateBasisPoints()).isEqualTo(6_667);
        ImpactFigures capped = ImpactFigures.of(new Raw(3, 3, 3, 3, 2, 5));
        assertThat(capped.firstLoansRepaidOnPlan()).isEqualTo(2);
        assertThat(capped.firstLoanOnPlanRateBasisPoints()).isEqualTo(10_000);
    }

    @Test
    void onTimeRateIsInstalmentsNotMissedOverInstalmentsDue() {
        OnTimeRate r = OnTimeRate.of(40, 3);

        assertThat(r.rateBasisPoints()).isEqualTo(9_250);
        assertThat(OnTimeRate.of(0, 0).rateBasisPoints()).isNull();
        assertThat(OnTimeRate.of(10, 0).rateBasisPoints()).isEqualTo(10_000);
        assertThat(OnTimeRate.of(10, 10).rateBasisPoints()).isZero();
    }

    @Test
    void onTimeRateNeverGoesNegativeWhenMissedExceedsDue() {
        OnTimeRate r = OnTimeRate.of(2, 5);

        assertThat(r.instalmentsMissed()).isEqualTo(2);
        assertThat(r.rateBasisPoints()).isZero();
    }

    @Test
    void slaBucketsHaveExactBoundaries() {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        Duration soon = Duration.ofHours(4);

        assertThat(SlaBucket.of(null, now, soon)).isEqualTo(SlaBucket.NO_SLA);
        assertThat(SlaBucket.of(now.minusSeconds(1), now, soon)).isEqualTo(SlaBucket.OVERDUE);
        assertThat(SlaBucket.of(now, now, soon)).isEqualTo(SlaBucket.OVERDUE);
        assertThat(SlaBucket.of(now.plusSeconds(1), now, soon)).isEqualTo(SlaBucket.DUE_SOON);
        assertThat(SlaBucket.of(now.plus(soon), now, soon)).isEqualTo(SlaBucket.DUE_SOON);
        assertThat(SlaBucket.of(now.plus(soon).plusSeconds(1), now, soon)).isEqualTo(SlaBucket.ON_TRACK);
    }

    @Test
    void assessmentOutcomeShowsTheRecommendationUntilAnOfficerDecides() {
        assertThat(AssessmentOutcome.of(true, "PENDING")).isEqualTo("RECOMMENDED");
        assertThat(AssessmentOutcome.of(false, null)).isEqualTo("NOT_RECOMMENDED");
        assertThat(AssessmentOutcome.of(false, "APPROVED")).isEqualTo("APPROVED");
        assertThat(AssessmentOutcome.of(true, "DECLINED")).isEqualTo("DECLINED");
        assertThat(AssessmentOutcome.of(true, "ADJUSTED")).isEqualTo("ADJUSTED");
        assertThat(AssessmentOutcome.status("SOMETHING_NEW")).isEqualTo("PENDING");
    }

    @Test
    void columnValuesRoundTripThroughTheirTextForm() {
        assertThat(ColumnType.INSTANT.parse(ColumnType.INSTANT.format(Instant.parse("2026-11-10T12:00:00.123456Z"))))
                .isEqualTo(Instant.parse("2026-11-10T12:00:00.123456Z"));
        assertThat(ColumnType.DATE.parse("2026-11-10")).isEqualTo(java.time.LocalDate.of(2026, 11, 10));
        assertThat(ColumnType.LONG.parse(ColumnType.LONG.format(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
        assertThat(ColumnType.INT.parse("-4")).isEqualTo(-4);
        assertThat(ColumnType.STRING.parse("")).isEqualTo("");
    }
}
