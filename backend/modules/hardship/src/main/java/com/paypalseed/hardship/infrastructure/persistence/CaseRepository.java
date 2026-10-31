package com.paypalseed.hardship.infrastructure.persistence;

import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.HARDSHIP_CASE;

import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseRow.Origin;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.infrastructure.persistence.jooq.tables.records.HardshipCaseRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class CaseRepository {

    /** Applied cases on the loan, counted by option, for the frequency caps of 03 §7. */
    public record AppliedCounts(int aInWindow, int bInWindow, int bTotal, int cTotal) {
    }

    private static final List<String> TERMINAL = CaseStatus.terminalStates().stream().map(Enum::name).toList();

    private final DSLContext dsl;

    CaseRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Returns false when the dedupe key or the one-open-case-per-loan index already holds a case. */
    public boolean insert(CaseRow c) {
        return dsl.insertInto(HARDSHIP_CASE)
                .set(HARDSHIP_CASE.ID, c.id())
                .set(HARDSHIP_CASE.LOAN_ID, c.loanId())
                .set(HARDSHIP_CASE.MEMBER_ID, c.memberId())
                .set(HARDSHIP_CASE.ORIGIN, c.origin().name())
                .set(HARDSHIP_CASE.STATUS, c.status().name())
                .set(HARDSHIP_CASE.RUN_ID, c.runId())
                .set(HARDSHIP_CASE.BASE_VERSION, c.baseVersion())
                .set(HARDSHIP_CASE.TARGET_SEQ, c.targetSeq())
                .set(HARDSHIP_CASE.TARGET_DUE_DATE, c.targetDueDate())
                .set(HARDSHIP_CASE.TARGET_AMOUNT_CENTS, c.targetAmountCents())
                .set(HARDSHIP_CASE.RISK_LEVEL, c.riskLevel())
                .set(HARDSHIP_CASE.POINTS, c.points())
                .set(HARDSHIP_CASE.POLICY_VERSION, c.policyVersion())
                .set(HARDSHIP_CASE.SCORECARD_VERSION, c.scorecardVersion())
                .set(HARDSHIP_CASE.REASON_CATEGORY, c.reasonCategory())
                .set(HARDSHIP_CASE.DEDUPE_KEY, c.dedupeKey())
                .set(HARDSHIP_CASE.OPENED_ON, c.openedOn())
                .set(HARDSHIP_CASE.AGENT_DEADLINE_AT, c.agentDeadlineAt())
                .onConflictDoNothing()
                .execute() == 1;
    }

    public Optional<CaseRow> find(UUID id) {
        return dsl.selectFrom(HARDSHIP_CASE).where(HARDSHIP_CASE.ID.eq(id)).fetchOptional(CaseRepository::map);
    }

    /** Every write path to a case is serialised on this row lock. */
    public Optional<CaseRow> lock(UUID id) {
        return dsl.selectFrom(HARDSHIP_CASE).where(HARDSHIP_CASE.ID.eq(id)).forUpdate().fetchOptional(CaseRepository::map);
    }

    public Optional<CaseRow> openForLoan(UUID loanId) {
        return dsl.selectFrom(HARDSHIP_CASE)
                .where(HARDSHIP_CASE.LOAN_ID.eq(loanId).and(HARDSHIP_CASE.STATUS.notIn(TERMINAL)))
                .fetchOptional(CaseRepository::map);
    }

    /** The member's newest open case, for SMS replies that do not name a loan. */
    public Optional<CaseRow> latestOpenForMember(UUID memberId) {
        return dsl.selectFrom(HARDSHIP_CASE)
                .where(HARDSHIP_CASE.MEMBER_ID.eq(memberId).and(HARDSHIP_CASE.STATUS.notIn(TERMINAL)))
                .orderBy(HARDSHIP_CASE.ID.desc())
                .limit(1)
                .fetchOptional(CaseRepository::map);
    }

    public Optional<CaseRow> byApprovalRequest(UUID requestId) {
        return dsl.selectFrom(HARDSHIP_CASE).where(HARDSHIP_CASE.APPROVAL_REQUEST_ID.eq(requestId))
                .fetchOptional(CaseRepository::map);
    }

    /** True while a proactive case closed less than the cool-down ago (03 §3.1). */
    public boolean inCooldown(UUID loanId, LocalDate asOf) {
        return dsl.fetchExists(HARDSHIP_CASE, HARDSHIP_CASE.LOAN_ID.eq(loanId)
                .and(HARDSHIP_CASE.COOLDOWN_UNTIL.isNotNull())
                .and(HARDSHIP_CASE.COOLDOWN_UNTIL.gt(asOf)));
    }

    /** Proactive outreaches the member received since {@code since}, across loans (outreach limits). */
    public int outreachesSince(UUID memberId, Instant since) {
        return dsl.fetchCount(HARDSHIP_CASE, HARDSHIP_CASE.MEMBER_ID.eq(memberId)
                .and(HARDSHIP_CASE.ORIGIN.eq(Origin.PROACTIVE_SCAN.name()))
                .and(HARDSHIP_CASE.OUTREACH_SENT_AT.ge(since)));
    }

    public AppliedCounts appliedCounts(UUID loanId, LocalDate windowStart) {
        Condition applied = HARDSHIP_CASE.LOAN_ID.eq(loanId).and(HARDSHIP_CASE.STATUS.eq(CaseStatus.APPLIED.name()));
        Condition inWindow = HARDSHIP_CASE.CLOSED_ON.ge(windowStart);
        return new AppliedCounts(
                dsl.fetchCount(HARDSHIP_CASE, applied.and(HARDSHIP_CASE.CHOSEN_OPTION.eq("A")).and(inWindow)),
                dsl.fetchCount(HARDSHIP_CASE, applied.and(HARDSHIP_CASE.CHOSEN_OPTION.eq("B")).and(inWindow)),
                dsl.fetchCount(HARDSHIP_CASE, applied.and(HARDSHIP_CASE.CHOSEN_OPTION.eq("B"))),
                dsl.fetchCount(HARDSHIP_CASE, applied.and(HARDSHIP_CASE.CHOSEN_OPTION.eq("C"))));
    }

    public List<CaseRow> forLoan(UUID loanId) {
        return dsl.selectFrom(HARDSHIP_CASE).where(HARDSHIP_CASE.LOAN_ID.eq(loanId)).orderBy(HARDSHIP_CASE.ID.desc())
                .fetch(CaseRepository::map);
    }

    public void setStatus(UUID id, CaseStatus status) {
        touch(dsl.update(HARDSHIP_CASE).set(HARDSHIP_CASE.STATUS, status.name()), id);
    }

    public void close(UUID id, CaseStatus status, LocalDate closedOn, String reason, LocalDate cooldownUntil) {
        dsl.update(HARDSHIP_CASE)
                .set(HARDSHIP_CASE.STATUS, status.name())
                .set(HARDSHIP_CASE.CLOSED_ON, closedOn)
                .set(HARDSHIP_CASE.CLOSED_REASON, reason)
                .set(HARDSHIP_CASE.COOLDOWN_UNTIL, cooldownUntil)
                .set(HARDSHIP_CASE.ROW_VERSION, HARDSHIP_CASE.ROW_VERSION.plus(1))
                .set(HARDSHIP_CASE.UPDATED_AT, PgSql.now())
                .where(HARDSHIP_CASE.ID.eq(id))
                .execute();
    }

    /** Claims the single first outreach of the case; false when it was already sent. */
    public boolean claimOutreach(UUID id, Instant at) {
        return dsl.update(HARDSHIP_CASE)
                .set(HARDSHIP_CASE.OUTREACH_SENT_AT, at)
                .set(HARDSHIP_CASE.UPDATED_AT, PgSql.now())
                .where(HARDSHIP_CASE.ID.eq(id).and(HARDSHIP_CASE.OUTREACH_SENT_AT.isNull()))
                .execute() == 1;
    }

    public boolean claimReminder(UUID id, Instant at) {
        return dsl.update(HARDSHIP_CASE)
                .set(HARDSHIP_CASE.REMINDER_SENT_AT, at)
                .where(HARDSHIP_CASE.ID.eq(id).and(HARDSHIP_CASE.REMINDER_SENT_AT.isNull()))
                .execute() == 1;
    }

    public void setNarrativeSource(UUID id, String source) {
        touch(dsl.update(HARDSHIP_CASE).set(HARDSHIP_CASE.NARRATIVE_SOURCE, source), id);
    }

    public void setRisk(UUID id, String level, int points) {
        touch(dsl.update(HARDSHIP_CASE).set(HARDSHIP_CASE.RISK_LEVEL, level).set(HARDSHIP_CASE.POINTS, points), id);
    }

    public void setChoice(UUID id, String label, CaseStatus status) {
        touch(dsl.update(HARDSHIP_CASE).set(HARDSHIP_CASE.CHOSEN_OPTION, label).set(HARDSHIP_CASE.STATUS, status.name()), id);
    }

    public void setApprovalRequest(UUID id, UUID requestId) {
        touch(dsl.update(HARDSHIP_CASE).set(HARDSHIP_CASE.APPROVAL_REQUEST_ID, requestId), id);
    }

    /** A stale offer sends the case back to the member: the choice and the approval request no longer apply. */
    public void resetChoice(UUID id, CaseStatus status, int baseVersion) {
        touch(dsl.update(HARDSHIP_CASE)
                .set(HARDSHIP_CASE.STATUS, status.name())
                .set(HARDSHIP_CASE.CHOSEN_OPTION, (String) null)
                .set(HARDSHIP_CASE.APPROVAL_REQUEST_ID, (UUID) null)
                .set(HARDSHIP_CASE.BASE_VERSION, baseVersion), id);
    }

    public void setApplied(UUID id, int version) {
        touch(dsl.update(HARDSHIP_CASE).set(HARDSHIP_CASE.APPLIED_VERSION, version), id);
    }

    /** The desk queue: keyset by case id (UUIDv7, so newest first), filters from a fixed allowlist only. */
    public List<CaseRow> queue(Collection<String> statuses, Collection<String> levels, Collection<String> origins,
                               UUID before, int limit) {
        Condition where = DSL.trueCondition();
        if (!statuses.isEmpty()) {
            where = where.and(HARDSHIP_CASE.STATUS.in(statuses));
        }
        if (!levels.isEmpty()) {
            where = where.and(HARDSHIP_CASE.RISK_LEVEL.in(levels));
        }
        if (!origins.isEmpty()) {
            where = where.and(HARDSHIP_CASE.ORIGIN.in(origins));
        }
        if (before != null) {
            where = where.and(HARDSHIP_CASE.ID.lt(before));
        }
        return dsl.selectFrom(HARDSHIP_CASE).where(where).orderBy(HARDSHIP_CASE.ID.desc()).limit(limit)
                .fetch(CaseRepository::map);
    }

    private void touch(org.jooq.UpdateSetMoreStep<HardshipCaseRecord> update, UUID id) {
        update.set(HARDSHIP_CASE.ROW_VERSION, HARDSHIP_CASE.ROW_VERSION.plus(1))
                .set(HARDSHIP_CASE.UPDATED_AT, PgSql.now())
                .where(HARDSHIP_CASE.ID.eq(id))
                .execute();
    }

    private static CaseRow map(HardshipCaseRecord r) {
        return new CaseRow(r.getId(), r.getLoanId(), r.getMemberId(), Origin.valueOf(r.getOrigin()),
                CaseStatus.valueOf(r.getStatus()), r.getRunId(), r.getBaseVersion(), r.getTargetSeq(), r.getTargetDueDate(),
                r.getTargetAmountCents(), r.getRiskLevel(), r.getPoints(), r.getPolicyVersion(), r.getScorecardVersion(),
                r.getReasonCategory(), r.getDedupeKey(), r.getOpenedOn(), r.getNarrativeSource(), r.getAgentDeadlineAt(),
                r.getOutreachSentAt(), r.getReminderSentAt(), r.getChosenOption(), r.getApprovalRequestId(),
                r.getAppliedVersion(), r.getClosedOn(), r.getClosedReason(), r.getCooldownUntil());
    }
}
