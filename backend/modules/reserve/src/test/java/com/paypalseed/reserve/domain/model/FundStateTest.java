package com.paypalseed.reserve.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.DomainException;
import org.junit.jupiter.api.Test;

class FundStateTest {

    @Test
    void donationsAddToBalanceAndCount() {
        FundState fund = FundState.EMPTY.donate(2_500).donate(1);
        assertThat(fund.balanceCents()).isEqualTo(2_501);
        assertThat(fund.totalDonatedCents()).isEqualTo(2_501);
        assertThat(fund.donationCount()).isEqualTo(2);
        assertThat(fund.totalPaidOutCents()).isZero();
    }

    @Test
    void payOutReducesBalanceAndCountsEachMemberOnce() {
        FundState fund = FundState.EMPTY.donate(10_000);
        FundState afterFirst = fund.payOut(4_000, true);
        FundState afterSecond = afterFirst.payOut(1_000, false);
        assertThat(afterSecond.balanceCents()).isEqualTo(5_000);
        assertThat(afterSecond.totalPaidOutCents()).isEqualTo(5_000);
        assertThat(afterSecond.membersCovered()).isEqualTo(1);
        assertThat(afterSecond.totalDonatedCents()).isEqualTo(10_000);
    }

    @Test
    void balanceCanReachExactlyZeroButNeverBelow() {
        FundState fund = FundState.EMPTY.donate(5_000);
        assertThat(fund.payOut(5_000, true).balanceCents()).isZero();
        assertThatThrownBy(() -> fund.payOut(5_001, true))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("errorCode", ReserveErrors.RESERVE_INSUFFICIENT_FUNDS);
        assertThatThrownBy(() -> FundState.EMPTY.payOut(1, true)).isInstanceOf(DomainException.class);
    }

    @Test
    void zeroAndNegativeAmountsAreRefused() {
        FundState fund = FundState.EMPTY.donate(100);
        assertThatThrownBy(() -> fund.donate(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fund.donate(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fund.payOut(0, false)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> fund.payOut(-1, false)).isInstanceOf(DomainException.class);
    }

    @Test
    void overflowIsRefusedNotWrapped() {
        FundState nearlyFull = new FundState(Long.MAX_VALUE - 10, Long.MAX_VALUE - 10, 0, 0, 0);
        assertThat(nearlyFull.donate(10).balanceCents()).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> nearlyFull.donate(11)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("errorCode", ReserveErrors.FUND_LIMIT_REACHED);

        FundState manyDonations = new FundState(0, 0, 0, Integer.MAX_VALUE, 0);
        assertThatThrownBy(() -> manyDonations.donate(1)).isInstanceOf(DomainException.class);
    }

    @Test
    void anImpossibleStateCannotBeBuilt() {
        assertThatThrownBy(() -> new FundState(-1, 0, 1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FundState(100, 100, 1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FundState(0, 0, 0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void balanceAlwaysEqualsDonatedMinusPaidOutOverAnySequence() {
        FundState fund = FundState.EMPTY;
        long[] gifts = {1, 99, 1_001, 25_000, 3, 7_777};
        for (long gift : gifts) {
            fund = fund.donate(gift);
        }
        for (long cover : new long[] {1_000, 500, 8_000}) {
            fund = fund.payOut(cover, true);
            assertThat(fund.balanceCents()).isEqualTo(fund.totalDonatedCents() - fund.totalPaidOutCents()).isNotNegative();
        }
    }
}
