package com.paypalseed.deskquery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.deskquery.domain.model.DeskBriefingFigures.BucketCount;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeskBriefingFiguresTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Test
    void currentLoansAreHealthyAndEveryLaterBucketIsAtRisk() {
        DeskBriefingFigures f = DeskBriefingFigures.of(List.of(
                new BucketCount("CURRENT", 10, 1_000_000), new BucketCount("DPD_1_7", 2, 150_000),
                new BucketCount("DPD_8_30", 1, 80_000), new BucketCount("DPD_31_60", 0, 0),
                new BucketCount("DPD_60_PLUS", 1, 60_000)), 2, Optional.empty(), TODAY, 0);

        assertThat(f.activeLoans()).isEqualTo(14);
        assertThat(f.healthyLoans()).isEqualTo(10);
        assertThat(f.atRiskLoans()).isEqualTo(4);
        assertThat(f.healthyLoans() + f.atRiskLoans()).isEqualTo(f.activeLoans());
        assertThat(f.outstandingCents()).isEqualTo(1_290_000);
        assertThat(f.openHardshipCases()).isEqualTo(2);
    }

    @Test
    void anUnknownBucketNameIsNotCountedAsHealthy() {
        DeskBriefingFigures f = DeskBriefingFigures.of(List.of(new BucketCount("DPD_90_PLUS", 3, 9_000)), 0,
                Optional.empty(), TODAY, 0);

        assertThat(f.healthyLoans()).isZero();
        assertThat(f.atRiskLoans()).isEqualTo(3);
    }

    @Test
    void theOldestCaseAgeIsWholeBusinessDaysOfTwentyFourHours() {
        DeskBriefingFigures f = DeskBriefingFigures.of(List.of(), 1, Optional.of(LocalDate.of(2026, 11, 6)), TODAY, 2);

        assertThat(f.oldestOpenCaseHours()).isEqualTo(72);
        assertThat(f.slaBreaches()).isEqualTo(2);
    }

    @Test
    void aCaseOpenedTodayOrInTheFutureIsZeroHoursOld() {
        assertThat(DeskBriefingFigures.of(List.of(), 1, Optional.of(TODAY), TODAY, 0).oldestOpenCaseHours()).isZero();
        assertThat(DeskBriefingFigures.of(List.of(), 1, Optional.of(TODAY.plusDays(2)), TODAY, 0).oldestOpenCaseHours())
                .isZero();
    }

    @Test
    void nothingOnTheBooksIsAllZero() {
        DeskBriefingFigures f = DeskBriefingFigures.of(List.of(), 0, Optional.empty(), TODAY, 0);

        assertThat(f).isEqualTo(new DeskBriefingFigures(0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void negativeFiguresAreRefused() {
        assertThatThrownBy(() -> new DeskBriefingFigures(-1, 0, 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
