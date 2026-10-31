package com.paypalseed.engines.schedule;

import static com.paypalseed.engines.schedule.Fixtures.POLICY;
import static com.paypalseed.engines.schedule.Fixtures.d;
import static com.paypalseed.engines.schedule.Fixtures.receipt;
import static com.paypalseed.engines.schedule.Fixtures.refund;
import static com.paypalseed.engines.schedule.Fixtures.reversal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.engines.schedule.Allocation.Portion;
import com.paypalseed.engines.schedule.MoneyEvent.Receipt;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TC-ALC-*: Ana's loan, 8 × $150 due on the 9th from 2026-12-09. */
class AllocatorTest {

    private final Schedule ana = Fixtures.ana();

    private Allocation allocate(MoneyEvent... events) {
        return Allocator.allocate(ana, List.of(events), POLICY);
    }

    @Test
    void tcAlc01_exactPaymentOnTheDueDate() {
        Allocation a = allocate(receipt("2026-12-09", 150));
        assertThat(a.of(1).isFullyPaid()).isTrue();
        assertThat(a.of(1).fullyPaidOn()).isEqualTo(d("2026-12-09"));
        assertThat(a.outstandingCents()).isEqualTo(105_000);
        assertThat(InstalmentStatus.of(ana.instalment(1), a.of(1), d("2026-12-09"), POLICY)).isEqualTo(InstalmentStatus.PAID);
    }

    @Test
    void tcAlc02_paymentInsideTheDueWindowPaysTheUpcomingInstalment() {
        Allocation a = allocate(receipt("2026-12-04", 150));
        assertThat(a.of(1).isFullyPaid()).isTrue();
        assertThat(a.of(8).paidCents()).isZero();
    }

    @Test
    void tcAlc03_paymentBeforeTheWindowShortensTheLoan() {
        Allocation a = allocate(receipt("2026-11-29", 150));
        assertThat(a.of(1).paidCents()).isZero();
        assertThat(a.of(8).isFullyPaid()).isTrue();
        assertThat(InstalmentStatus.of(ana.instalment(8), a.of(8), d("2026-11-29"), POLICY)).isEqualTo(InstalmentStatus.PREPAID);
    }

    @Test
    void tcAlc04_partialThenRest() {
        Allocation partial = allocate(receipt("2026-12-09", 100));
        assertThat(partial.of(1).remainingCents()).isEqualTo(5_000);
        assertThat(InstalmentStatus.of(ana.instalment(1), partial.of(1), d("2026-12-09"), POLICY))
                .isEqualTo(InstalmentStatus.PARTIALLY_PAID);

        Allocation full = allocate(receipt("2026-12-09", 100), receipt("2026-12-09", 50));
        assertThat(full.of(1).isFullyPaid()).isTrue();
    }

    @Test
    void tcAlc05_catchUpPaysOldestFirst() {
        Allocation a = allocate(receipt("2027-01-20", 300));
        assertThat(a.of(1).isFullyPaid()).isTrue();
        assertThat(a.of(2).isFullyPaid()).isTrue();
        assertThat(a.of(1).fullyPaidOn()).isEqualTo(d("2027-01-20"));
        assertThat(InstalmentStatus.of(ana.instalment(1), a.of(1), d("2027-01-20"), POLICY))
                .isEqualTo(InstalmentStatus.PAID_LATE);
    }

    @Test
    void tcAlc06_excessOverWhatIsDueGoesToTheLastInstalment() {
        Receipt r = receipt("2026-12-09", 200);
        Allocation a = allocate(r);
        assertThat(a.of(1).isFullyPaid()).isTrue();
        assertThat(a.of(8).paidCents()).isEqualTo(5_000);
        assertThat(a.portionsOf(r.id())).containsExactly(new Portion(1, 15_000), new Portion(8, 5_000));
    }

    @Test
    void tcAlc07_doublePaymentShortensTheLoanAndKeepsNextInstalmentDue() {
        Allocation a = allocate(receipt("2027-01-09", 300), receipt("2027-01-09", 150));
        assertThat(a.of(1).isFullyPaid()).isTrue();
        assertThat(a.of(2).isFullyPaid()).isTrue();
        assertThat(a.of(3).paidCents()).isZero(); // next autopay still collects instalment 3 in full
        assertThat(a.of(8).isFullyPaid()).isTrue();
    }

    @Test
    void tcAlc08_paymentBeyondOutstandingBecomesOverpayment() {
        Allocation a = allocate(receipt("2026-12-09", 1_250));
        assertThat(a.outstandingCents()).isZero();
        assertThat(a.overpaymentCents()).isEqualTo(5_000);
    }

    @Test
    void tcAlc09_reversalReopensTheInstalment() {
        Receipt paid = receipt("2026-12-09", 150);
        Allocation a = allocate(paid, reversal(paid, "2026-12-20", 150));
        assertThat(a.of(1).paidCents()).isZero();
        Aging aging = Aging.of(ana, a, d("2026-12-20"), POLICY);
        assertThat(aging.dpd()).isEqualTo(11);
        assertThat(aging.bucket()).isEqualTo(Bucket.DPD_8_30);
    }

    @Test
    void tcAlc10_resultDoesNotDependOnArrivalOrder() {
        Receipt dec = receipt("2026-12-09", 150);
        Receipt jan = receipt("2027-01-09", 150);
        Allocation inOrder = Allocator.allocate(ana, List.of(dec, jan), POLICY);
        Allocation reversed = Allocator.allocate(ana, List.of(jan, dec), POLICY);
        assertThat(reversed).isEqualTo(inOrder);
    }

    @Test
    void overpaymentRefundDrainsThePool() {
        Allocation a = allocate(receipt("2026-12-09", 1_250), refund("2026-12-10", 50));
        assertThat(a.overpaymentCents()).isZero();
        assertThat(a.outstandingCents()).isZero();
    }

    @Test
    void refundLargerThanThePoolReopensTheLatestInstalments() {
        // $1,250 received, $50 refunded, then $100 of the original payment is charged back:
        // the chargeback sits at the receipt's position, so the earlier refund over-returned $100.
        Receipt big = receipt("2026-12-09", 1_250);
        Allocation a = allocate(big, refund("2026-12-10", 50), reversal(big, "2026-12-15", 100));
        assertThat(a.overpaymentCents()).isZero();
        assertThat(a.outstandingCents()).isEqualTo(10_000);
        // net $1,150 left $50 unpaid on instalment 2; the $50 refund shortfall re-opened instalment 8
        assertThat(a.of(2).remainingCents()).isEqualTo(5_000);
        assertThat(a.of(8).remainingCents()).isEqualTo(5_000);
    }

    @Test
    void refundsExceedingAllMoneyAreAnInvariantBreach() {
        assertThatThrownBy(() -> allocate(refund("2026-12-10", 10))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reversalOfUnknownReceiptIsRejected() {
        Receipt ghost = receipt("2026-12-09", 10);
        assertThatThrownBy(() -> allocate(reversal(ghost, "2026-12-10", 10))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextInstallmentRulePaysAhead() {
        RepaymentPolicy next = new RepaymentPolicy("next", 5, 14, 3, 3, 7,
                RepaymentPolicy.FutureInstalmentRule.NEXT_INSTALLMENT, RepaymentPolicy.RemainderPlacement.BACK,
                2, 4, 2, 14, 6, 2_500, 3, 36);
        Allocation a = Allocator.allocate(ana, List.of(receipt("2026-12-09", 200)), next);
        assertThat(a.of(2).paidCents()).isEqualTo(5_000);
        assertThat(a.of(8).paidCents()).isZero();
    }
}
