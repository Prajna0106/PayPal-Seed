package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.domain.model.AgingBuckets;
import com.paypalseed.deskquery.domain.model.NextDue.Instalment;
import com.paypalseed.deskquery.infrastructure.persistence.LoanViewRepository;
import com.paypalseed.lending.api.events.LendingEvents.CollectionModeChanged;
import com.paypalseed.lending.api.events.LendingEvents.InstalmentMissed;
import com.paypalseed.lending.api.events.LendingEvents.InstalmentView;
import com.paypalseed.lending.api.events.LendingEvents.LateBucketChanged;
import com.paypalseed.lending.api.events.LendingEvents.LoanRepaid;
import com.paypalseed.lending.api.events.LendingEvents.LoanReopened;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReversed;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleChanged;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleGenerated;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the loan projection from lending's facts. Every method is an idempotent, guarded upsert on the loan id, so an
 * event delivered twice or after a newer one leaves the row as it was (see {@link LoanViewRepository}). Events that
 * reach a loan before its schedule do not make it visible: the grids list a loan once its schedule has been seen.
 */
@Service
public class LoanProjector {

    private static final String COLLECTION_AUTOPAY = "AUTOPAY";

    private final LoanViewRepository loans;

    LoanProjector(LoanViewRepository loans) {
        this.loans = loans;
    }

    @Transactional
    public void on(ScheduleGenerated e) {
        loans.ensure(e.loanId(), e.memberId());
        long total = e.instalments().stream().mapToLong(InstalmentView::amountCents).sum();
        loans.initialise(e.loanId(), total, e.businessDate());
        loans.applySchedule(e.loanId(), e.version(), instalments(e.instalments()));
    }

    @Transactional
    public void on(ScheduleChanged e) {
        loans.ensure(e.loanId(), e.memberId());
        loans.applySchedule(e.loanId(), e.version(), instalments(e.instalments()));
    }

    @Transactional
    public void on(RepaymentReceived e) {
        loans.ensure(e.loanId(), e.memberId());
        loans.recordMoney(e.loanId(), e.businessDate(), e.outstandingCents(), e.loanStatus());
    }

    @Transactional
    public void on(RepaymentReversed e) {
        loans.ensure(e.loanId(), e.memberId());
        loans.recordMoney(e.loanId(), e.businessDate(), e.outstandingCents(), null);
    }

    /** A loan repaid with no missed instalment was repaid on plan. */
    @Transactional
    public void on(LoanRepaid e) {
        loans.ensure(e.loanId(), e.memberId());
        loans.recordRepaid(e.loanId(), e.businessDate(), e.instalmentsMissed() == 0);
    }

    @Transactional
    public void on(LoanReopened e) {
        loans.ensure(e.loanId(), e.memberId());
        loans.recordReopened(e.loanId(), e.businessDate(), e.outstandingCents());
    }

    @Transactional
    public void on(LateBucketChanged e) {
        loans.ensure(e.loanId(), e.memberId());
        loans.recordDelinquency(e.loanId(), e.businessDate(), AgingBuckets.resolve(e.toBucket(), e.dpd()), Math.max(0, e.dpd()));
    }

    @Transactional
    public void on(InstalmentMissed e) {
        loans.ensure(e.loanId(), e.memberId());
        loans.recordMissed(e.loanId(), e.version(), e.seq());
    }

    @Transactional
    public void on(CollectionModeChanged e) {
        loans.ensure(e.loanId(), e.memberId());
        loans.recordCollectionMode(e.loanId(), e.businessDate(), COLLECTION_AUTOPAY.equals(e.toMode()));
    }

    /** Reached by other projectors that learn a loan has a hardship case. */
    @Transactional
    public void refreshHardshipFlag(UUID loanId, UUID memberRef) {
        loans.ensure(loanId, memberRef);
        loans.refreshHardshipFlag(loanId);
    }

    private static List<Instalment> instalments(List<InstalmentView> views) {
        return views.stream().map(v -> new Instalment(v.seq(), v.dueDate(), v.amountCents())).toList();
    }
}
