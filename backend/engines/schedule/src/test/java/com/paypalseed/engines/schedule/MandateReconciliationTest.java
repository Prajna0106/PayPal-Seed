package com.paypalseed.engines.schedule;

import static com.paypalseed.engines.schedule.Fixtures.POLICY;
import static com.paypalseed.engines.schedule.Fixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engines.schedule.MandateReconciliation.Activate;
import com.paypalseed.engines.schedule.MandateReconciliation.Cancel;
import com.paypalseed.engines.schedule.MandateReconciliation.CollectionSpec;
import com.paypalseed.engines.schedule.MandateReconciliation.Desired;
import com.paypalseed.engines.schedule.MandateReconciliation.DueAmount;
import com.paypalseed.engines.schedule.MandateReconciliation.MandateStatus;
import com.paypalseed.engines.schedule.MandateReconciliation.Observed;
import com.paypalseed.engines.schedule.MandateReconciliation.Reason;
import com.paypalseed.engines.schedule.MandateReconciliation.Revise;
import com.paypalseed.engines.schedule.MandateReconciliation.State;
import com.paypalseed.engines.schedule.MandateReconciliation.Suspend;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** TC-MND-03..08, PROP-MND-01. */
class MandateReconciliationTest {

    private static final LocalDate TODAY = d("2027-03-01");

    private static Observed active(long qty, String nextBilling) {
        return new Observed(MandateStatus.ACTIVE, qty, Optional.of(d(nextBilling)));
    }

    private static CollectionSpec unpaid(Object... dueAndDollars) {
        List<DueAmount> list = new ArrayList<>();
        for (int i = 0; i < dueAndDollars.length; i += 2) {
            list.add(new DueAmount(d((String) dueAndDollars[i]), ((Integer) dueAndDollars[i + 1]) * 100L));
        }
        return new CollectionSpec(true, list);
    }

    @Test
    void steadyStateNeedsNoCommands() {
        Observed observed = active(150, "2027-03-09");
        Desired desired = MandateReconciliation.desired(unpaid("2027-03-09", 150, "2027-04-09", 150), observed, TODAY, POLICY);
        assertThat(desired.state()).isEqualTo(State.ACTIVE);
        assertThat(MandateReconciliation.plan(desired, observed)).isEmpty();
    }

    @Test
    void tcMnd03_pausedMonthSuspends() {
        Observed observed = active(150, "2027-03-09");
        Desired desired = MandateReconciliation.desired(unpaid("2027-04-09", 150, "2027-10-09", 150), observed, TODAY, POLICY);
        assertThat(desired.reason()).isEqualTo(Reason.NO_INSTALMENT_THIS_CYCLE);
        assertThat(desired.drift()).isFalse();
        assertThat(MandateReconciliation.plan(desired, observed)).containsExactly(new Suspend());
    }

    @Test
    void tcMnd04_finalInstalmentWithRemainderDollarRevisesQuantity() {
        Observed observed = active(150, "2027-07-09");
        Desired desired = MandateReconciliation.desired(unpaid("2027-07-09", 151), observed, d("2027-07-01"), POLICY);
        assertThat(MandateReconciliation.plan(desired, observed)).containsExactly(new Revise(151));
    }

    @Test
    void tcMnd05_fullyPaidCancels() {
        Observed observed = active(150, "2027-07-09");
        Desired desired = MandateReconciliation.desired(new CollectionSpec(true, List.of()), observed, TODAY, POLICY);
        assertThat(MandateReconciliation.plan(desired, observed)).containsExactly(new Cancel());
    }

    @Test
    void suspendedMandateIsReactivatedWithTheRightAmount() {
        Observed observed = new Observed(MandateStatus.SUSPENDED, 150, Optional.empty());
        Desired desired = MandateReconciliation.desired(unpaid("2027-04-09", 75), observed, TODAY, POLICY);
        assertThat(MandateReconciliation.plan(desired, observed)).containsExactly(new Revise(75), new Activate());
    }

    @Test
    void tcMnd08_billingDateFarFromAnUpcomingDueDateIsDrift() {
        Observed observed = active(150, "2027-03-20");
        Desired desired = MandateReconciliation.desired(unpaid("2027-03-09", 150), observed, TODAY, POLICY);
        assertThat(desired.drift()).isTrue();
        assertThat(desired.state()).isEqualTo(State.SUSPENDED);
    }

    @Test
    void pendingApprovalIsNeverSteered() {
        Observed pending = new Observed(MandateStatus.APPROVAL_PENDING, 150, Optional.empty());
        Desired cancel = MandateReconciliation.desired(new CollectionSpec(false, List.of()), pending, TODAY, POLICY);
        assertThat(MandateReconciliation.plan(cancel, pending)).isEmpty();
    }

    @Property
    void propMnd01_planningConverges(
            @ForAll MandateStatus status,
            @ForAll @IntRange(min = 1, max = 400) int qty,
            @ForAll @IntRange(min = -10, max = 40) int billingOffset,
            @ForAll @IntRange(min = 0, max = 4) int unpaidCount,
            @ForAll @IntRange(min = 1, max = 28) int dueDay,
            @ForAll boolean collectible) {
        List<DueAmount> due = new ArrayList<>();
        for (int k = 0; k < unpaidCount; k++) {
            due.add(new DueAmount(DueDates.dateOf(java.time.YearMonth.from(TODAY).plusMonths(k), dueDay), 100L * (100 + k)));
        }
        CollectionSpec spec = new CollectionSpec(collectible, due);
        Observed observed = new Observed(status, qty, Optional.of(TODAY.plusDays(billingOffset)));

        Desired desired = MandateReconciliation.desired(spec, observed, TODAY, POLICY);
        Observed after = MandateReconciliation.apply(observed, MandateReconciliation.plan(desired, observed));
        Desired again = MandateReconciliation.desired(spec, after, TODAY, POLICY);
        assertThat(MandateReconciliation.plan(again, after)).isEmpty();
    }
}
