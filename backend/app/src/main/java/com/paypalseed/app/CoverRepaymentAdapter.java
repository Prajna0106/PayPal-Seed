package com.paypalseed.app;

import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.CoverResult;
import com.paypalseed.lending.api.dto.LendingDtos.ReserveCover;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.reserve.api.events.ReserveEvents.CoverApproved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Carries a reserve cover onto the loan's schedule (ADR 0019, ADR 0020). Reserve consumes lending's events for its
 * exposure figure, so lending cannot import reserve without a cycle that Spring Modulith rejects even through events
 * (the same reason as {@link RecordFeed}, ADR 0014). This wiring module may depend on both: it hears
 * {@link CoverApproved} and hands lending plain facts.
 *
 * <p>Reserve has already posted the cover to the ledger, so lending records the repayment and posts nothing.
 * Delivery is at least once and lending is idempotent on the approval reference, so a repeat changes nothing.
 */
@Component
class CoverRepaymentAdapter {

    private static final Logger log = LoggerFactory.getLogger(CoverRepaymentAdapter.class);

    private final LendingFacade lending;
    private final BusinessClock clock;

    CoverRepaymentAdapter(LendingFacade lending, BusinessClock clock) {
        this.lending = lending;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(CoverApproved e) {
        CoverResult result = lending.applyReserveCover(
            new ReserveCover(e.loanId(), e.amountCents(), e.approvalRef(), e.journalEntryId(), e.businessDate(), clock.now()));
        log.info("Reserve cover {} for loan {}: {} ({} cents applied, {} cents unused)", e.approvalRef(), e.loanId(),
            result.outcome(), result.appliedCents(), result.unusedCents());
    }
}
