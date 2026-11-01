package com.paypalseed.governance.infrastructure.persistence;

import static com.paypalseed.governance.infrastructure.persistence.jooq.Tables.APPROVAL;
import static com.paypalseed.governance.infrastructure.persistence.jooq.Tables.APPROVAL_REQUEST;

import com.paypalseed.governance.infrastructure.persistence.jooq.tables.records.ApprovalRecord;
import com.paypalseed.governance.infrastructure.persistence.jooq.tables.records.ApprovalRequestRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code governance.approval_request} (state) and {@code governance.approval} (append-only decisions). */
@Repository
public class ApprovalRepository {

    public record RequestRow(UUID id, UUID subjectId, UUID subjectMemberId, String tier, UUID offerId, String snapshotHash,
                             boolean checkerRequired, String status, Instant slaDueAt, Instant openedAt, Instant closedAt,
                             String closedReason) {
    }

    public record DecisionRow(UUID id, UUID requestId, String role, UUID staffId, UUID staffMemberId, String decision,
                              String reasonCode, long evidenceViewedMs, String idempotencyKey, Instant decidedAt) {
    }

    /** {@code startedAt} is when the decision became the staff member's to take. */
    public record DecisionTiming(String decision, long evidenceViewedMs, Instant startedAt, Instant decidedAt) {
    }

    private final DSLContext dsl;

    public ApprovalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insertRequest(UUID id, UUID subjectId, UUID subjectMemberId, String tier, UUID offerId, String snapshotHash,
                              boolean checkerRequired, String status, Instant slaDueAt, String closedReason) {
        boolean closed = !"OPEN".equals(status);
        dsl.insertInto(APPROVAL_REQUEST)
                .set(APPROVAL_REQUEST.ID, id)
                .set(APPROVAL_REQUEST.SUBJECT_ID, subjectId)
                .set(APPROVAL_REQUEST.SUBJECT_MEMBER_ID, subjectMemberId)
                .set(APPROVAL_REQUEST.TIER, tier)
                .set(APPROVAL_REQUEST.OFFER_ID, offerId)
                .set(APPROVAL_REQUEST.SNAPSHOT_HASH, snapshotHash)
                .set(APPROVAL_REQUEST.CHECKER_REQUIRED, checkerRequired)
                .set(APPROVAL_REQUEST.STATUS, status)
                .set(APPROVAL_REQUEST.SLA_DUE_AT, slaDueAt)
                .set(APPROVAL_REQUEST.CLOSED_AT, closed ? PgSql.now() : null)
                .set(APPROVAL_REQUEST.CLOSED_REASON, closedReason)
                .execute();
    }

    public Optional<RequestRow> find(UUID id) {
        return dsl.selectFrom(APPROVAL_REQUEST).where(APPROVAL_REQUEST.ID.eq(id)).fetchOptional(ApprovalRepository::request);
    }

    /** Row lock for the rest of the transaction: decisions on one request are serialised here. */
    public Optional<RequestRow> lock(UUID id) {
        return dsl.selectFrom(APPROVAL_REQUEST).where(APPROVAL_REQUEST.ID.eq(id)).forUpdate()
                .fetchOptional(ApprovalRepository::request);
    }

    public Optional<RequestRow> openFor(UUID subjectId) {
        return dsl.selectFrom(APPROVAL_REQUEST)
                .where(APPROVAL_REQUEST.SUBJECT_ID.eq(subjectId).and(APPROVAL_REQUEST.STATUS.eq("OPEN")))
                .fetchOptional(ApprovalRepository::request);
    }

    /** Closes an open request; returns false when it was no longer open. */
    public boolean close(UUID id, String status, String reason) {
        return dsl.update(APPROVAL_REQUEST)
                .set(APPROVAL_REQUEST.STATUS, status)
                .set(APPROVAL_REQUEST.CLOSED_AT, PgSql.now())
                .set(APPROVAL_REQUEST.CLOSED_REASON, reason)
                .where(APPROVAL_REQUEST.ID.eq(id).and(APPROVAL_REQUEST.STATUS.eq("OPEN")))
                .execute() == 1;
    }

    public void insertDecision(UUID id, UUID requestId, String role, UUID staffId, UUID staffMemberId, String decision,
                               String reasonCode, String note, long evidenceViewedMs, String idempotencyKey) {
        dsl.insertInto(APPROVAL)
                .set(APPROVAL.ID, id)
                .set(APPROVAL.REQUEST_ID, requestId)
                .set(APPROVAL.ROLE, role)
                .set(APPROVAL.STAFF_ID, staffId)
                .set(APPROVAL.STAFF_MEMBER_ID, staffMemberId)
                .set(APPROVAL.DECISION, decision)
                .set(APPROVAL.REASON_CODE, reasonCode)
                .set(APPROVAL.NOTE, note)
                .set(APPROVAL.EVIDENCE_VIEWED_MS, evidenceViewedMs)
                .set(APPROVAL.IDEMPOTENCY_KEY, idempotencyKey)
                .execute();
    }

    public List<DecisionRow> decisions(UUID requestId) {
        return dsl.selectFrom(APPROVAL).where(APPROVAL.REQUEST_ID.eq(requestId)).orderBy(APPROVAL.DECIDED_AT, APPROVAL.ROLE.desc())
                .fetch(ApprovalRepository::decision);
    }

    /** The officer's latest decisions, newest first, with when each became theirs to take. */
    public List<DecisionTiming> recentByStaff(UUID staffId, int limit) {
        var maker = APPROVAL.as("maker");
        return dsl.select(APPROVAL.DECISION, APPROVAL.EVIDENCE_VIEWED_MS, APPROVAL.ROLE, APPROVAL_REQUEST.OPENED_AT,
                        maker.DECIDED_AT, APPROVAL.DECIDED_AT)
                .from(APPROVAL)
                .join(APPROVAL_REQUEST).on(APPROVAL_REQUEST.ID.eq(APPROVAL.REQUEST_ID))
                .leftJoin(maker).on(maker.REQUEST_ID.eq(APPROVAL.REQUEST_ID).and(maker.ROLE.eq("MAKER")))
                .where(APPROVAL.STAFF_ID.eq(staffId))
                .orderBy(APPROVAL.DECIDED_AT.desc())
                .limit(limit)
                .fetch(r -> new DecisionTiming(r.value1(), r.value2(),
                        "CHECKER".equals(r.value3()) && r.value5() != null ? r.value5() : r.value4(), r.value6()));
    }

    private static RequestRow request(ApprovalRequestRecord r) {
        return new RequestRow(r.getId(), r.getSubjectId(), r.getSubjectMemberId(), r.getTier(), r.getOfferId(),
                r.getSnapshotHash(), r.getCheckerRequired(), r.getStatus(), r.getSlaDueAt(), r.getOpenedAt(), r.getClosedAt(),
                r.getClosedReason());
    }

    private static DecisionRow decision(ApprovalRecord r) {
        return new DecisionRow(r.getId(), r.getRequestId(), r.getRole(), r.getStaffId(), r.getStaffMemberId(), r.getDecision(),
                r.getReasonCode(), r.getEvidenceViewedMs(), r.getIdempotencyKey(), r.getDecidedAt());
    }
}
