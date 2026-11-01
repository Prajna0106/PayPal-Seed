package com.paypalseed.hardship.infrastructure.events;

import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalDecided;
import com.paypalseed.hardship.application.usecases.ApprovalFlow;
import com.paypalseed.hardship.application.usecases.CaseReactions;
import com.paypalseed.hardship.application.usecases.HardshipScan;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.lending.api.events.LendingEvents;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lending facts that wake the scorecard for one loan between daily scans, or end an open case; and governance's
 * decisions, which start the execution saga. Delivery is at least once: every handler is idempotent.
 */
@Component
class HardshipTriggers {

    private final HardshipScan scan;
    private final CaseReactions reactions;
    private final ApprovalFlow approvals;
    private final CaseRepository cases;
    private final TransactionTemplate tx;

    HardshipTriggers(HardshipScan scan, CaseReactions reactions, ApprovalFlow approvals, CaseRepository cases,
                     PlatformTransactionManager txManager) {
        this.scan = scan;
        this.reactions = reactions;
        this.approvals = approvals;
        this.cases = cases;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @ApplicationModuleListener
    void on(LendingEvents.RepaymentFailed e) {
        scan.wake(e.loanId());
    }

    @ApplicationModuleListener
    void on(LendingEvents.GraceNudgeDue e) {
        scan.wake(e.loanId());
    }

    @ApplicationModuleListener
    void on(LendingEvents.InstalmentDueSoon e) {
        scan.wake(e.loanId());
    }

    @ApplicationModuleListener
    void on(LendingEvents.LateBucketChanged e) {
        scan.wake(e.loanId());
    }

    @ApplicationModuleListener
    void on(LendingEvents.RepaymentReceived e) {
        reactions.onPayment(e.loanId(), false);
    }

    @ApplicationModuleListener
    void on(LendingEvents.LoanCured e) {
        reactions.onPayment(e.loanId(), false);
    }

    @ApplicationModuleListener
    void on(LendingEvents.LoanRepaid e) {
        reactions.onPayment(e.loanId(), true);
    }

    @ApplicationModuleListener
    void on(LendingEvents.ScheduleChanged e) {
        reactions.onScheduleChanged(e.loanId(), e.causedBy());
    }

    /**
     * Not one transaction around both steps: the case's approval commits first, then the saga applies in its own
     * transaction, so a failing apply is retried instead of undoing the recorded approval.
     */
    @Async
    @TransactionalEventListener
    void on(ApprovalDecided e) {
        Boolean approved = tx.execute(status -> approvals.onDecided(e));
        if (Boolean.TRUE.equals(approved)) {
            cases.byApprovalRequest(e.requestId()).ifPresent(c -> approvals.execute(c.id()));
        }
    }
}
