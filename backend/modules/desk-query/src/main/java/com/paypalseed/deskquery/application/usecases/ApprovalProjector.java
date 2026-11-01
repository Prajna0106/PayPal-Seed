package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.infrastructure.persistence.ApprovalViewRepository;
import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalDecided;
import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalRequested;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open approval requests for the workload view. A maker's approval that leaves the request OPEN moves it to the checker; any
 * other decision finishes it. Auto-approved (POLICY) requests only ever appear finished.
 */
@Service
public class ApprovalProjector {

    private static final String OPEN = "OPEN";
    private static final String MAKER = "MAKER";

    private final ApprovalViewRepository approvals;

    ApprovalProjector(ApprovalViewRepository approvals) {
        this.approvals = approvals;
    }

    @Transactional
    public void on(ApprovalRequested e) {
        approvals.requested(e.requestId(), e.subjectId(), e.tier(), e.slaDueAt());
    }

    @Transactional
    public void on(ApprovalDecided e) {
        int stage;
        if (!OPEN.equals(e.requestStatus())) {
            stage = ApprovalViewRepository.FINISHED;
        } else {
            stage = MAKER.equals(e.role()) ? ApprovalViewRepository.WAITING_FOR_CHECKER : ApprovalViewRepository.WAITING_FOR_MAKER;
        }
        approvals.decided(e.requestId(), e.subjectId(), e.requestStatus(), stage);
    }
}
