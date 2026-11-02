package com.paypalseed.deskquery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class AgingBucketsTest {

    @ParameterizedTest
    @CsvSource({
            "0,CURRENT", "1,DPD_1_7", "7,DPD_1_7", "8,DPD_8_30", "30,DPD_8_30", "31,DPD_31_60", "60,DPD_31_60",
            "61,DPD_60_PLUS", "365,DPD_60_PLUS", "2147483647,DPD_60_PLUS"})
    void bucketsFollowTheScheduleEnginesRanges(int daysPastDue, String bucket) {
        assertThat(AgingBuckets.of(daysPastDue)).isEqualTo(bucket);
    }

    @Test
    void negativeDaysPastDueAreCurrent() {
        assertThat(AgingBuckets.of(-3)).isEqualTo("CURRENT");
    }

    @Test
    void orderRunsFromHealthyToWorst() {
        assertThat(AgingBuckets.ORDER).containsExactly("CURRENT", "DPD_1_7", "DPD_8_30", "DPD_31_60", "DPD_60_PLUS");
        assertThat(AgingBuckets.ORDER.stream().map(AgingBuckets::rank).toList()).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void everyDayMapsToExactlyOneBucketAndBucketsNeverGetBetterAsDaysGrow() {
        int previousRank = 0;
        for (int dpd = 0; dpd <= 400; dpd++) {
            int rank = AgingBuckets.rank(AgingBuckets.of(dpd));
            assertThat(rank).isGreaterThanOrEqualTo(previousRank);
            previousRank = rank;
        }
    }

    @Test
    void keepsTheBucketLendingReportedWhenItIsKnown() {
        assertThat(AgingBuckets.resolve("DPD_8_30", 9)).isEqualTo("DPD_8_30");
    }

    @Test
    void fallsBackToTheDaysPastDueForABucketItDoesNotKnow() {
        assertThat(AgingBuckets.resolve("DPD_90_PLUS", 95)).isEqualTo("DPD_60_PLUS");
        assertThat(AgingBuckets.resolve(null, 3)).isEqualTo("DPD_1_7");
        assertThat(AgingBuckets.resolve("", 0)).isEqualTo("CURRENT");
    }

    @Test
    void unknownBucketsRankAsCurrent() {
        assertThat(AgingBuckets.rank("MYSTERY")).isZero();
        assertThat(List.of("CURRENT", "DPD_60_PLUS")).allMatch(AgingBuckets::isKnown);
        assertThat(AgingBuckets.isKnown("current")).isFalse();
    }
}
