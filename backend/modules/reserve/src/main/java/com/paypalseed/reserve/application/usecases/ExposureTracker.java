package com.paypalseed.reserve.application.usecases;

import com.paypalseed.lending.api.events.LendingEvents.InstalmentView;
import com.paypalseed.lending.api.events.LendingEvents.LoanReopened;
import com.paypalseed.lending.api.events.LendingEvents.LoanRepaid;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReversed;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleGenerated;
import com.paypalseed.reserve.infrastructure.persistence.ExposureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open exposure from lending's events: the total a member still owes is what the fund stands behind. Each handler
 * writes the balance the event itself states, so repeated or reordered delivery cannot drift the figure.
 */
@Service
public class ExposureTracker {

    private final ExposureRepository exposure;

    public ExposureTracker(ExposureRepository exposure) {
        this.exposure = exposure;
    }

    /** A 0% loan owes exactly its scheduled instalments when it is created. */
    @Transactional
    public void on(ScheduleGenerated e) {
        exposure.set(e.loanId(), e.instalments().stream().mapToLong(InstalmentView::amountCents).sum(), e.eventId());
    }

    @Transactional
    public void on(RepaymentReceived e) {
        exposure.set(e.loanId(), e.outstandingCents(), e.eventId());
    }

    @Transactional
    public void on(RepaymentReversed e) {
        exposure.set(e.loanId(), e.outstandingCents(), e.eventId());
    }

    @Transactional
    public void on(LoanReopened e) {
        exposure.set(e.loanId(), e.outstandingCents(), e.eventId());
    }

    @Transactional
    public void on(LoanRepaid e) {
        exposure.set(e.loanId(), 0, e.eventId());
    }
}
