package com.paypalseed.reserve.infrastructure.events;

import com.paypalseed.lending.api.events.LendingEvents.LoanVoided;
import com.paypalseed.reserve.application.usecases.CoverReturns;
import com.paypalseed.reserve.application.usecases.ExposureTracker;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A loan whose payout came back is void (ADR 0024). The fund no longer stands behind anything on it, and the cover the
 * fund paid on it is back in the fund: lending's own ledger entry credited the reserve, so this brings the fund's
 * counters and the covers in line. Delivery is at least once; both steps are idempotent.
 */
@Component
class VoidedLoanListener {

    private final ExposureTracker exposure;
    private final CoverReturns coverReturns;

    VoidedLoanListener(ExposureTracker exposure, CoverReturns coverReturns) {
        this.exposure = exposure;
        this.coverReturns = coverReturns;
    }

    @ApplicationModuleListener
    void on(LoanVoided e) {
        exposure.on(e);
        coverReturns.onLoanVoided(e.loanId(), e.reserveCoverCents());
    }
}
