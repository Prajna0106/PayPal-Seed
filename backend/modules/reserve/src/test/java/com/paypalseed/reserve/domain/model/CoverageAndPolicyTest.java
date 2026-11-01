package com.paypalseed.reserve.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.DomainException;
import org.junit.jupiter.api.Test;

class CoverageAndPolicyTest {

    @Test
    void ratioIsRoundedDownInBasisPoints() {
        assertThat(Coverage.ratioBps(1, 3)).isEqualTo(3_333L);
        assertThat(Coverage.ratioBps(2, 3)).isEqualTo(6_666L);
        assertThat(Coverage.ratioBps(125_000, 900_000)).isEqualTo(1_388L);
        assertThat(Coverage.ratioBps(900_000, 900_000)).isEqualTo(10_000L);
        assertThat(Coverage.ratioBps(0, 500)).isZero();
    }

    @Test
    void noExposureMeansNoRatio() {
        assertThat(Coverage.ratioBps(50_000, 0)).isNull();
        assertThat(Coverage.ratioBps(0, 0)).isNull();
    }

    @Test
    void hugeAmountsStayExact() {
        assertThat(Coverage.ratioBps(Long.MAX_VALUE, Long.MAX_VALUE)).isEqualTo(10_000L);
        assertThat(Coverage.ratioBps(Long.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void negativeInputIsAProgrammingError() {
        assertThatThrownBy(() -> Coverage.ratioBps(-1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coverage.ratioBps(1, -5)).isInstanceOf(IllegalArgumentException.class);
    }

    private final CoverPolicy policy = new CoverPolicy(50_000, 100_000);

    @Test
    void amountsWithinBothLimitsPass() {
        policy.check(50_000, 0);
        policy.check(50_000, 50_000);
        policy.check(1, 99_999);
    }

    @Test
    void oneRequestAboveTheCapIsRefused() {
        assertThatThrownBy(() -> policy.check(50_001, 0)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("errorCode", ReserveErrors.COVER_AMOUNT_EXCEEDS_REQUEST_CAP);
    }

    @Test
    void theYearlyCapCountsWhatWasAlreadyCommitted() {
        assertThatThrownBy(() -> policy.check(30_000, 80_000)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("errorCode", ReserveErrors.COVER_MEMBER_CAP_EXCEEDED)
                .satisfies(e -> assertThat(((DomainException) e).details()).containsEntry("remainingThisYearCents", 20_000L));
        assertThatThrownBy(() -> policy.check(1, 100_000)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> policy.check(1, 250_000)).isInstanceOf(DomainException.class);
    }

    @Test
    void limitsMustMakeSense() {
        assertThatThrownBy(() -> new CoverPolicy(0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoverPolicy(500, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.check(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
