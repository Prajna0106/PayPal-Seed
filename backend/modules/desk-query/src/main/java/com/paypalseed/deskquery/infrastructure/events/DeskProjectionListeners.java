package com.paypalseed.deskquery.infrastructure.events;

import com.paypalseed.deskquery.application.usecases.ApplicationProjector;
import com.paypalseed.deskquery.application.usecases.ApprovalProjector;
import com.paypalseed.deskquery.application.usecases.AssessmentProjector;
import com.paypalseed.deskquery.application.usecases.HardshipProjector;
import com.paypalseed.deskquery.application.usecases.LoanProjector;
import com.paypalseed.deskquery.application.usecases.SavingsProjector;
import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalDecided;
import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalRequested;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipApplied;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipCaseClosed;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipCaseOpened;
import com.paypalseed.lending.api.events.LendingEvents.CollectionModeChanged;
import com.paypalseed.lending.api.events.LendingEvents.InstalmentMissed;
import com.paypalseed.lending.api.events.LendingEvents.LateBucketChanged;
import com.paypalseed.lending.api.events.LendingEvents.LoanReopened;
import com.paypalseed.lending.api.events.LendingEvents.LoanRepaid;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReversed;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleChanged;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleGenerated;
import com.paypalseed.lending.api.events.LoanApplicationChanged;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.api.events.GoalCreatedEvent;
import com.paypalseed.underwriting.api.events.AssessmentChanged;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The desk's only inputs: facts other modules publish. Delivery is at least once and may be out of order, so every handler
 * delegates to a projector whose writes are guarded upserts keyed by the source business key. A listener that fails
 * stays in the publication log and is retried; replaying the log rebuilds a projection (ADR 0015).
 *
 * <p>Loan applications and fair-check assessments are projected by {@code ApplicationProjector} and
 * {@code AssessmentProjector} from the whole-state snapshots lending ({@code LoanApplicationChanged}) and underwriting
 * ({@code AssessmentChanged}) publish, so a late or repeated delivery cannot corrupt a queue (ADR 0015, ADR 0021).
 */
@Component
class DeskProjectionListeners {

    private final LoanProjector loans;
    private final HardshipProjector hardship;
    private final SavingsProjector savings;
    private final ApprovalProjector approvals;
    private final ApplicationProjector applications;
    private final AssessmentProjector assessments;

    DeskProjectionListeners(LoanProjector loans, HardshipProjector hardship, SavingsProjector savings, ApprovalProjector approvals,
                            ApplicationProjector applications, AssessmentProjector assessments) {
        this.loans = loans;
        this.hardship = hardship;
        this.savings = savings;
        this.approvals = approvals;
        this.applications = applications;
        this.assessments = assessments;
    }

    @ApplicationModuleListener
    void on(ScheduleGenerated e) {
        loans.on(e);
    }

    @ApplicationModuleListener
    void on(ScheduleChanged e) {
        loans.on(e);
    }

    @ApplicationModuleListener
    void on(RepaymentReceived e) {
        loans.on(e);
    }

    @ApplicationModuleListener
    void on(RepaymentReversed e) {
        loans.on(e);
    }

    @ApplicationModuleListener
    void on(LoanRepaid e) {
        loans.on(e);
    }

    @ApplicationModuleListener
    void on(LoanReopened e) {
        loans.on(e);
    }

    @ApplicationModuleListener
    void on(LateBucketChanged e) {
        loans.on(e);
    }

    @ApplicationModuleListener
    void on(InstalmentMissed e) {
        loans.on(e);
    }

    @ApplicationModuleListener
    void on(CollectionModeChanged e) {
        loans.on(e);
    }

    @ApplicationModuleListener
    void on(HardshipCaseOpened e) {
        hardship.on(e);
    }

    @ApplicationModuleListener
    void on(HardshipApplied e) {
        hardship.on(e);
    }

    @ApplicationModuleListener
    void on(HardshipCaseClosed e) {
        hardship.on(e);
    }

    @ApplicationModuleListener
    void on(GoalCreatedEvent e) {
        savings.on(e);
    }

    @ApplicationModuleListener
    void on(DepositReceivedEvent e) {
        savings.on(e);
    }

    @ApplicationModuleListener
    void on(ApprovalRequested e) {
        approvals.on(e);
    }

    @ApplicationModuleListener
    void on(ApprovalDecided e) {
        approvals.on(e);
    }

    /** A snapshot, so the newest {@code changedAt} wins whatever the delivery order; any status lending names is stored. */
    @ApplicationModuleListener
    void on(LoanApplicationChanged e) {
        applications.apply(new ApplicationProjector.Snapshot(e.applicationId(), e.memberId(), e.status(), e.requestedAmountCents(),
                e.termMonths(), e.submittedAt(), e.assignedOfficerRef(), e.changedAt()));
    }

    @ApplicationModuleListener
    void on(AssessmentChanged e) {
        assessments.apply(new AssessmentProjector.Snapshot(e.assessmentId(), e.memberId(), e.applicationId(), e.recommended(),
                e.recommendedAmountCents(), e.officerDecision(), e.memberDisputed(), e.createdAt(), e.changedAt()));
    }
}
