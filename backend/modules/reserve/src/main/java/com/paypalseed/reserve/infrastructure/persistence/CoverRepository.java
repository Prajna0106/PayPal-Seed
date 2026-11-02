package com.paypalseed.reserve.infrastructure.persistence;

import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.COVER_REQUEST;

import com.paypalseed.platform.persistence.PgSql;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverStatus;
import com.paypalseed.reserve.infrastructure.persistence.jooq.tables.records.CoverRequestRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Cover requests: the fund's decisions about paying a member's loan. */
@Repository
public class CoverRepository {

    public record CoverRow(UUID id, UUID memberId, UUID loanId, long amountCents, String reasonCode, String note,
                           UUID hardshipCaseId, CoverStatus status, UUID approvalRequestId, UUID requestedBy,
                           LocalDate requestedOn, String idempotencyKey, String requestHash, UUID journalEntryId,
                           String closedReason) {
    }

    private final DSLContext dsl;

    CoverRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(CoverRow c) {
        dsl.insertInto(COVER_REQUEST)
                .set(COVER_REQUEST.ID, c.id())
                .set(COVER_REQUEST.MEMBER_ID, c.memberId())
                .set(COVER_REQUEST.LOAN_ID, c.loanId())
                .set(COVER_REQUEST.AMOUNT_CENTS, c.amountCents())
                .set(COVER_REQUEST.REASON_CODE, c.reasonCode())
                .set(COVER_REQUEST.NOTE, c.note())
                .set(COVER_REQUEST.HARDSHIP_CASE_ID, c.hardshipCaseId())
                .set(COVER_REQUEST.STATUS, c.status().name())
                .set(COVER_REQUEST.REQUESTED_BY, c.requestedBy())
                .set(COVER_REQUEST.REQUESTED_ON, c.requestedOn())
                .set(COVER_REQUEST.IDEMPOTENCY_KEY, c.idempotencyKey())
                .set(COVER_REQUEST.REQUEST_HASH, c.requestHash())
                .execute();
    }

    public Optional<CoverRow> find(UUID id) {
        return dsl.selectFrom(COVER_REQUEST).where(COVER_REQUEST.ID.eq(id)).fetchOptional(CoverRepository::row);
    }

    public Optional<CoverRow> lock(UUID id) {
        return dsl.selectFrom(COVER_REQUEST).where(COVER_REQUEST.ID.eq(id)).forUpdate().fetchOptional(CoverRepository::row);
    }

    public Optional<CoverRow> byKey(String idempotencyKey) {
        return dsl.selectFrom(COVER_REQUEST).where(COVER_REQUEST.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(CoverRepository::row);
    }

    public Optional<CoverRow> byApprovalRequest(UUID approvalRequestId) {
        return dsl.selectFrom(COVER_REQUEST).where(COVER_REQUEST.APPROVAL_REQUEST_ID.eq(approvalRequestId))
                .fetchOptional(CoverRepository::row);
    }

    public boolean hasOpenFor(UUID loanId) {
        return dsl.fetchExists(COVER_REQUEST,
                COVER_REQUEST.LOAN_ID.eq(loanId).and(COVER_REQUEST.STATUS.eq(CoverStatus.PENDING_APPROVAL.name())));
    }

    public void setApprovalRequest(UUID id, UUID approvalRequestId) {
        dsl.update(COVER_REQUEST).set(COVER_REQUEST.APPROVAL_REQUEST_ID, approvalRequestId)
                .where(COVER_REQUEST.ID.eq(id)).execute();
    }

    public void markApproved(UUID id, UUID journalEntryId) {
        dsl.update(COVER_REQUEST)
                .set(COVER_REQUEST.STATUS, CoverStatus.APPROVED.name())
                .set(COVER_REQUEST.JOURNAL_ENTRY_ID, journalEntryId)
                .set(COVER_REQUEST.CLOSED_REASON, "APPROVED")
                .set(COVER_REQUEST.CLOSED_AT, PgSql.now())
                .where(COVER_REQUEST.ID.eq(id).and(COVER_REQUEST.STATUS.eq(CoverStatus.PENDING_APPROVAL.name())))
                .execute();
    }

    /** Closes a pending request without paying: REJECTED by an officer, or FAILED when the money was not there. */
    public void markClosed(UUID id, CoverStatus status, String reason) {
        dsl.update(COVER_REQUEST)
                .set(COVER_REQUEST.STATUS, status.name())
                .set(COVER_REQUEST.CLOSED_REASON, reason)
                .set(COVER_REQUEST.CLOSED_AT, PgSql.now())
                .where(COVER_REQUEST.ID.eq(id).and(COVER_REQUEST.STATUS.eq(CoverStatus.PENDING_APPROVAL.name())))
                .execute();
    }

    /** The member's approved and pending covers requested in {@code [from, until)}: what the yearly cap counts. */
    public long committed(UUID memberId, LocalDate from, LocalDate until) {
        BigDecimal sum = dsl.select(DSL.coalesce(DSL.sum(COVER_REQUEST.AMOUNT_CENTS), BigDecimal.ZERO))
                .from(COVER_REQUEST)
                .where(COVER_REQUEST.MEMBER_ID.eq(memberId)
                        .and(COVER_REQUEST.REQUESTED_ON.ge(from)).and(COVER_REQUEST.REQUESTED_ON.lt(until))
                        .and(COVER_REQUEST.STATUS.in(CoverStatus.PENDING_APPROVAL.name(), CoverStatus.APPROVED.name())))
                .fetchOne(0, BigDecimal.class);
        return sum.longValueExact();
    }

    /** Keyset page, newest first: ids are time-ordered, so {@code before} is the previous page's last id. */
    public List<CoverRow> page(List<CoverStatus> statuses, UUID before, int limit) {
        Condition where = DSL.trueCondition();
        if (!statuses.isEmpty()) {
            where = where.and(COVER_REQUEST.STATUS.in(statuses.stream().map(Enum::name).toList()));
        }
        if (before != null) {
            where = where.and(COVER_REQUEST.ID.lt(before));
        }
        return dsl.selectFrom(COVER_REQUEST).where(where).orderBy(COVER_REQUEST.ID.desc()).limit(limit)
                .fetch(CoverRepository::row);
    }

    private static CoverRow row(CoverRequestRecord r) {
        return new CoverRow(r.getId(), r.getMemberId(), r.getLoanId(), r.getAmountCents(), r.getReasonCode(), r.getNote(),
                r.getHardshipCaseId(), CoverStatus.valueOf(r.getStatus()), r.getApprovalRequestId(), r.getRequestedBy(),
                r.getRequestedOn(), r.getIdempotencyKey(), r.getRequestHash(), r.getJournalEntryId(), r.getClosedReason());
    }
}
