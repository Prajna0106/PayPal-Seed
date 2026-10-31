package com.paypalseed.governance.application.usecases;

import static com.paypalseed.governance.application.usecases.ProposalService.json;

import com.paypalseed.governance.api.ApprovalStaleException;
import com.paypalseed.governance.api.dto.GovernanceDtos.ApprovalView;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decide;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decision;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionStat;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionView;
import com.paypalseed.governance.api.dto.GovernanceDtos.OpenApproval;
import com.paypalseed.governance.api.dto.GovernanceDtos.Tier;
import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalDecided;
import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalRequested;
import com.paypalseed.governance.domain.model.GovernanceErrors;
import com.paypalseed.governance.domain.model.StaffRefs;
import com.paypalseed.governance.infrastructure.persistence.ApprovalRepository;
import com.paypalseed.governance.infrastructure.persistence.ApprovalRepository.DecisionRow;
import com.paypalseed.governance.infrastructure.persistence.ApprovalRepository.RequestRow;
import com.paypalseed.governance.infrastructure.persistence.AuditRepository;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maker-checker approvals. Separation of duties is checked here for a clear error and again by a deferred
 * database trigger, so a bypass of this service still cannot record an invalid decision.
 */
@Service
public class ApprovalService {

    static final String MAKER = "MAKER";
    static final String CHECKER = "CHECKER";
    private static final String OFFICER_ROLE = "OFFICER";
    private static final String CHECKER_ROLE = "CHECKER";

    private final ApprovalRepository approvals;
    private final AuditRepository auditEntries;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    public ApprovalService(ApprovalRepository approvals, AuditRepository auditEntries, AuditTrail audit,
                           ApplicationEventPublisher events) {
        this.approvals = approvals;
        this.auditEntries = auditEntries;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public UUID open(OpenApproval command) {
        if (command.tier() == Tier.AUTO_POLICY && command.checkerRequired()) {
            throw new IllegalArgumentException("AUTO_POLICY requests have no checker");
        }
        Optional<RequestRow> open = approvals.openFor(command.subjectId());
        if (open.isPresent()) {
            RequestRow existing = open.get();
            if (existing.snapshotHash().equals(command.snapshotHash()) && existing.tier().equals(command.tier().name())) {
                return existing.id();
            }
            throw new DomainException(GovernanceErrors.APPROVAL_REQUEST_OPEN,
                    "Subject " + command.subjectId() + " already has an open approval request",
                    Map.of("requestId", existing.id().toString()));
        }
        UUID id = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", id);
        payload.put("tier", command.tier().name());
        payload.put("snapshotHash", command.snapshotHash());
        payload.put("checkerRequired", command.checkerRequired());
        if (command.tier() == Tier.AUTO_POLICY) {
            approvals.insertRequest(id, command.subjectId(), command.subjectMemberId(), command.tier().name(),
                    command.offerId(), command.snapshotHash(), false, "APPROVED", null, "POLICY");
            audit.append("APPROVAL_POLICY_APPROVED", command.subjectId(), "SYSTEM", "policy", json(payload));
            events.publishEvent(new ApprovalDecided(Ids.newId(), id, command.subjectId(), "POLICY",
                    Decision.APPROVE.name(), "APPROVED", "WITHIN_POLICY", "policy"));
            return id;
        }
        approvals.insertRequest(id, command.subjectId(), command.subjectMemberId(), command.tier().name(),
                command.offerId(), command.snapshotHash(), command.checkerRequired(), "OPEN", command.slaDueAt(), null);
        audit.append("APPROVAL_REQUESTED", command.subjectId(), "SYSTEM", "governance", json(payload));
        events.publishEvent(new ApprovalRequested(Ids.newId(), id, command.subjectId(), command.tier().name(),
                command.slaDueAt()));
        return id;
    }

    /** The void on a stale snapshot must survive the exception, hence {@code noRollbackFor}. */
    @Transactional(noRollbackFor = ApprovalStaleException.class)
    public DecisionResult decide(Decide command) {
        RequestRow request = approvals.lock(command.requestId()).orElseThrow(() -> new DomainException(
                GovernanceErrors.APPROVAL_REQUEST_NOT_FOUND, "No approval request " + command.requestId()));
        List<DecisionRow> decisions = approvals.decisions(request.id());
        Optional<DecisionRow> replay = decisions.stream()
                .filter(d -> d.idempotencyKey().equals(command.idempotencyKey()) && d.staffId().equals(command.staffId()))
                .findFirst();
        if (replay.isPresent()) {
            return replayed(request, replay.get());
        }
        if (!"OPEN".equals(request.status())) {
            throw new DomainException(GovernanceErrors.APPROVAL_REQUEST_CLOSED,
                    "Request " + request.id() + " is " + request.status(), Map.of("status", request.status()));
        }
        if (command.reasonCode() == null || command.reasonCode().isBlank()) {
            throw new DomainException(GovernanceErrors.REASON_CODE_REQUIRED, "Choose a reason code for the decision");
        }
        Optional<DecisionRow> maker = decisions.stream().filter(d -> MAKER.equals(d.role())).findFirst();
        String role = maker.isPresent() ? CHECKER : MAKER;
        requireRole(command, role);
        requireSeparation(command, request, maker);

        if (!request.snapshotHash().equals(command.currentSnapshotHash())) {
            approvals.close(request.id(), "VOIDED", "STALE_SNAPSHOT");
            audit.append("APPROVAL_VOIDED", request.subjectId(), "STAFF", command.staffId().toString(),
                    json(Map.of("requestId", request.id(), "reason", "STALE_SNAPSHOT")));
            events.publishEvent(new ApprovalDecided(Ids.newId(), request.id(), request.subjectId(), role, "VOID", "VOIDED",
                    "STALE_SNAPSHOT", StaffRefs.of(command.staffId())));
            throw new ApprovalStaleException("What the member chose no longer matches the current schedule",
                    Map.of("requestId", request.id().toString()));
        }

        UUID decisionId = Ids.newId();
        approvals.insertDecision(decisionId, request.id(), role, command.staffId(), command.staffMemberId(),
                command.decision().name(), command.reasonCode(), command.note(), Math.max(0, command.evidenceViewedMs()),
                command.idempotencyKey());
        String status;
        String next = null;
        if (command.decision() == Decision.REJECT) {
            status = "REJECTED";
        } else if (MAKER.equals(role) && request.checkerRequired()) {
            status = "OPEN";
            next = CHECKER;
        } else {
            status = "APPROVED";
        }
        if (!"OPEN".equals(status)) {
            approvals.close(request.id(), status, command.reasonCode());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.id());
        payload.put("decisionId", decisionId);
        payload.put("role", role);
        payload.put("decision", command.decision().name());
        payload.put("reasonCode", command.reasonCode());
        payload.put("evidenceViewedMs", Math.max(0, command.evidenceViewedMs()));
        payload.put("snapshotHash", request.snapshotHash());
        payload.put("requestStatus", status);
        long seq = audit.append("APPROVAL_DECISION", request.subjectId(), "STAFF", command.staffId().toString(), json(payload));
        events.publishEvent(new ApprovalDecided(Ids.newId(), request.id(), request.subjectId(), role,
                command.decision().name(), status, command.reasonCode(), StaffRefs.of(command.staffId())));
        return new DecisionResult(request.id(), status, role, next, seq);
    }

    @Transactional
    public void voidRequest(UUID requestId, String reason) {
        RequestRow request = approvals.lock(requestId).orElseThrow(() -> new DomainException(
                GovernanceErrors.APPROVAL_REQUEST_NOT_FOUND, "No approval request " + requestId));
        if (!"OPEN".equals(request.status())) {
            return;
        }
        approvals.close(requestId, "VOIDED", reason);
        audit.append("APPROVAL_VOIDED", request.subjectId(), "SYSTEM", "governance",
                json(Map.of("requestId", requestId, "reason", Objects.requireNonNullElse(reason, "UNSPECIFIED"))));
        events.publishEvent(new ApprovalDecided(Ids.newId(), requestId, request.subjectId(), "SYSTEM", "VOID", "VOIDED",
                reason, "system"));
    }

    @Transactional(readOnly = true)
    public Optional<ApprovalView> view(UUID requestId) {
        return approvals.find(requestId).map(this::view);
    }

    @Transactional(readOnly = true)
    public Optional<ApprovalView> openFor(UUID subjectId) {
        return approvals.openFor(subjectId).map(this::view);
    }

    @Transactional(readOnly = true)
    public List<DecisionStat> recentDecisions(UUID staffId, int limit) {
        return approvals.recentByStaff(staffId, Math.clamp(limit, 1, 200)).stream()
                .map(t -> new DecisionStat(Decision.APPROVE.name().equals(t.decision()),
                        Math.max(0, Duration.between(t.startedAt(), t.decidedAt()).toMillis()), t.evidenceViewedMs(),
                        t.decidedAt()))
                .toList();
    }

    private ApprovalView view(RequestRow r) {
        List<DecisionView> decisions = approvals.decisions(r.id()).stream()
                .map(d -> new DecisionView(d.role(), StaffRefs.of(d.staffId()), Decision.valueOf(d.decision()),
                        d.reasonCode(), d.decidedAt()))
                .toList();
        return new ApprovalView(r.id(), r.subjectId(), Tier.valueOf(r.tier()), r.status(), r.snapshotHash(), r.slaDueAt(),
                r.checkerRequired(), r.openedAt(), r.closedAt(), r.closedReason(), decisions);
    }

    private DecisionResult replayed(RequestRow request, DecisionRow decision) {
        String next = MAKER.equals(decision.role()) && "OPEN".equals(request.status()) ? CHECKER : null;
        long seq = auditEntries.seqOfDecision(request.subjectId(), decision.id()).orElse(0L);
        return new DecisionResult(request.id(), request.status(), decision.role(), next, seq);
    }

    private static void requireRole(Decide command, String role) {
        String needed = MAKER.equals(role) ? OFFICER_ROLE : CHECKER_ROLE;
        if (!command.staffRoles().contains(needed)) {
            throw new DomainException(GovernanceErrors.DECIDER_ROLE_REQUIRED,
                    "The " + role.toLowerCase() + " decision needs the " + needed + " role", Map.of("role", role));
        }
    }

    private static void requireSeparation(Decide command, RequestRow request, Optional<DecisionRow> maker) {
        UUID subjectMember = request.subjectMemberId();
        if (subjectMember != null
                && (subjectMember.equals(command.staffId()) || subjectMember.equals(command.staffMemberId()))) {
            throw new DomainException(GovernanceErrors.SOD_VIOLATION, "A member cannot decide their own request");
        }
        if (maker.isPresent()) {
            DecisionRow m = maker.get();
            boolean sameStaff = m.staffId().equals(command.staffId());
            boolean samePerson = m.staffMemberId() != null && m.staffMemberId().equals(command.staffMemberId());
            if (sameStaff || samePerson) {
                throw new DomainException(GovernanceErrors.SOD_VIOLATION, "The checker must be a different person from the maker");
            }
        }
    }
}
