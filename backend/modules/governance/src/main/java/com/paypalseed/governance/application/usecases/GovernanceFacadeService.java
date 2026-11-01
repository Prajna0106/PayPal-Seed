package com.paypalseed.governance.application.usecases;

import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.ApprovalView;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.governance.api.dto.GovernanceDtos.ChainVerification;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decide;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionStat;
import com.paypalseed.governance.api.dto.GovernanceDtos.OpenApproval;
import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.SubmitProposal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class GovernanceFacadeService implements GovernanceFacade {

    private final ProposalService proposals;
    private final ApprovalService approvals;
    private final AuditTrail audit;

    GovernanceFacadeService(ProposalService proposals, ApprovalService approvals, AuditTrail audit) {
        this.proposals = proposals;
        this.approvals = approvals;
        this.audit = audit;
    }

    @Override
    public ProposalResult submitProposal(SubmitProposal command) {
        return proposals.submit(command);
    }

    @Override
    public UUID openApprovalRequest(OpenApproval command) {
        return approvals.open(command);
    }

    @Override
    public DecisionResult decide(Decide command) {
        return approvals.decide(command);
    }

    @Override
    public void voidRequest(UUID requestId, String reason) {
        approvals.voidRequest(requestId, reason);
    }

    @Override
    public Optional<ApprovalView> request(UUID requestId) {
        return approvals.view(requestId);
    }

    @Override
    public Optional<ApprovalView> openRequestFor(UUID subjectId) {
        return approvals.openFor(subjectId);
    }

    @Override
    public List<DecisionStat> recentDecisions(UUID staffId, int limit) {
        return approvals.recentDecisions(staffId, limit);
    }

    @Override
    public long audit(AuditCommand command) {
        return audit.append(command.entryType(), command.subjectId(), command.actorType(), command.actorId(),
                command.payloadJson());
    }

    @Override
    public ChainVerification verifyChain() {
        return audit.verify();
    }
}
