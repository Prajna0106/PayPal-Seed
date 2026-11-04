package com.paypalseed.reserve.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Money coming back to the fund after a cover paid it out (ADR 0024). */
class FundStateReturnTest {

    private static final FundState AFTER_COVER = FundState.EMPTY.donate(50_000).payOut(20_000, true);

    @Test
    void aReturnRestoresTheBalanceAndLowersWhatWasPaidOutAndKeepsTheInvariant() {
        FundState back = AFTER_COVER.returnCover(6_000);

        assertThat(back.balanceCents()).isEqualTo(36_000);
        assertThat(back.totalPaidOutCents()).isEqualTo(14_000);
        assertThat(back.totalDonatedCents()).isEqualTo(50_000);
        assertThat(back.balanceCents()).isEqualTo(back.totalDonatedCents() - back.totalPaidOutCents());
        assertThat(back.membersCovered()).as("the fund did help this member").isEqualTo(1);
    }

    @Test
    void theWholeCoverCanComeBack() {
        FundState back = AFTER_COVER.returnCover(20_000);

        assertThat(back.balanceCents()).isEqualTo(50_000);
        assertThat(back.totalPaidOutCents()).isZero();
    }

    @Test
    void aReturnIsPositiveAndCannotExceedWhatWasEverPaidOut() {
        assertThatThrownBy(() -> AFTER_COVER.returnCover(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AFTER_COVER.returnCover(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AFTER_COVER.returnCover(20_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FundState.EMPTY.donate(100).returnCover(1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payingOutAgainAfterAReturnIsAllowedUpToTheRestoredBalance() {
        FundState again = AFTER_COVER.returnCover(20_000).payOut(50_000, false);

        assertThat(again.balanceCents()).isZero();
        assertThat(again.totalPaidOutCents()).isEqualTo(50_000);
    }
}
