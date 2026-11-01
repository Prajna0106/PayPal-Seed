package com.paypalseed.hardship.infrastructure.persistence;

import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.AGENT_OUTCOME;
import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.CASE_TIMER;
import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.NARRATIVE;
import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.SAGA;
import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.SMS_REPLY;
import static org.jooq.impl.DSL.excluded;

import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Narratives, durable timers, the execution saga and the idempotency records of a case. */
@Repository
public class CaseRecordsRepository {

    public record NarrativeRow(String audience, String locale, String text, String source, String grounding, UUID agentRunId,
                               Instant createdAt) {
    }

    public record DueTimer(UUID caseId, String kind, Instant fireAt) {
    }

    public record SagaRow(UUID caseId, String approvalRef, UUID requestId, UUID optionId, String status, int attempts,
                          String lastError, Integer appliedVersion) {
    }

    private final DSLContext dsl;
    private final JsonColumns json;

    CaseRecordsRepository(DSLContext dsl, JsonColumns json) {
        this.dsl = dsl;
        this.json = json;
    }

    public void addNarrative(UUID caseId, String audience, String locale, String text, String source, String grounding,
                             UUID agentRunId) {
        dsl.insertInto(NARRATIVE)
                .set(NARRATIVE.CASE_ID, caseId)
                .set(NARRATIVE.AUDIENCE, audience)
                .set(NARRATIVE.LOCALE, locale)
                .set(NARRATIVE.TEXT, text)
                .set(NARRATIVE.SOURCE, source)
                .set(NARRATIVE.GROUNDING, grounding)
                .set(NARRATIVE.AGENT_RUN_ID, agentRunId)
                .execute();
    }

    /** The newest text for the audience and locale that may be shown (shadow texts are kept for comparison only). */
    public Optional<NarrativeRow> latestNarrative(UUID caseId, String audience, String locale) {
        return dsl.selectFrom(NARRATIVE)
                .where(NARRATIVE.CASE_ID.eq(caseId).and(NARRATIVE.AUDIENCE.eq(audience)).and(NARRATIVE.LOCALE.eq(locale))
                        .and(NARRATIVE.SOURCE.ne("AI_SHADOW")))
                .orderBy(NARRATIVE.ID.desc())
                .limit(1)
                .fetchOptional(r -> new NarrativeRow(r.getAudience(), r.getLocale(), r.getText(), r.getSource(),
                        r.getGrounding(), r.getAgentRunId(), r.getCreatedAt()));
    }

    public List<NarrativeRow> narratives(UUID caseId) {
        return dsl.selectFrom(NARRATIVE).where(NARRATIVE.CASE_ID.eq(caseId)).orderBy(NARRATIVE.ID)
                .fetch(r -> new NarrativeRow(r.getAudience(), r.getLocale(), r.getText(), r.getSource(), r.getGrounding(),
                        r.getAgentRunId(), r.getCreatedAt()));
    }

    /** Plans (or re-plans) an unfired timer; a timer that already fired stays fired. */
    public void schedule(UUID caseId, String kind, Instant fireAt) {
        dsl.insertInto(CASE_TIMER)
                .set(CASE_TIMER.CASE_ID, caseId)
                .set(CASE_TIMER.KIND, kind)
                .set(CASE_TIMER.FIRE_AT, fireAt)
                .onConflict(CASE_TIMER.CASE_ID, CASE_TIMER.KIND)
                .doUpdate()
                .set(CASE_TIMER.FIRE_AT, excluded(CASE_TIMER.FIRE_AT))
                .where(CASE_TIMER.FIRED_AT.isNull())
                .execute();
    }

    /** Re-arms a timer that may have fired before (saga retries). */
    public void rearm(UUID caseId, String kind, Instant fireAt) {
        dsl.insertInto(CASE_TIMER)
                .set(CASE_TIMER.CASE_ID, caseId)
                .set(CASE_TIMER.KIND, kind)
                .set(CASE_TIMER.FIRE_AT, fireAt)
                .onConflict(CASE_TIMER.CASE_ID, CASE_TIMER.KIND)
                .doUpdate()
                .set(CASE_TIMER.FIRE_AT, excluded(CASE_TIMER.FIRE_AT))
                .set(CASE_TIMER.FIRED_AT, (Instant) null)
                .execute();
    }

    /** Stops the unfired timers of a case (it closed or moved on); they keep their rows as fired. */
    public void cancelTimers(UUID caseId, List<String> kinds) {
        dsl.update(CASE_TIMER).set(CASE_TIMER.FIRED_AT, PgSql.now())
                .where(CASE_TIMER.CASE_ID.eq(caseId).and(CASE_TIMER.KIND.in(kinds)).and(CASE_TIMER.FIRED_AT.isNull()))
                .execute();
    }

    /** Claims due timers; the rows stay locked until the claiming transaction ends, so sweepers never double-fire. */
    public List<DueTimer> claimDue(Instant now, int limit) {
        return dsl.selectFrom(CASE_TIMER)
                .where(CASE_TIMER.FIRED_AT.isNull().and(CASE_TIMER.FIRE_AT.le(now)))
                .orderBy(CASE_TIMER.FIRE_AT)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(r -> new DueTimer(r.getCaseId(), r.getKind(), r.getFireAt()));
    }

    public void markFired(UUID caseId, String kind) {
        dsl.update(CASE_TIMER).set(CASE_TIMER.FIRED_AT, PgSql.now())
                .where(CASE_TIMER.CASE_ID.eq(caseId).and(CASE_TIMER.KIND.eq(kind)))
                .execute();
    }

    public Optional<Instant> timer(UUID caseId, String kind) {
        return dsl.select(CASE_TIMER.FIRE_AT).from(CASE_TIMER)
                .where(CASE_TIMER.CASE_ID.eq(caseId).and(CASE_TIMER.KIND.eq(kind)).and(CASE_TIMER.FIRED_AT.isNull()))
                .fetchOptional(CASE_TIMER.FIRE_AT);
    }

    public boolean insertSaga(UUID caseId, String approvalRef, UUID requestId, UUID optionId) {
        return dsl.insertInto(SAGA)
                .set(SAGA.CASE_ID, caseId)
                .set(SAGA.APPROVAL_REF, approvalRef)
                .set(SAGA.REQUEST_ID, requestId)
                .set(SAGA.OPTION_ID, optionId)
                .set(SAGA.STATUS, "PENDING")
                .onConflictDoNothing()
                .execute() == 1;
    }

    public Optional<SagaRow> saga(UUID caseId) {
        return dsl.selectFrom(SAGA).where(SAGA.CASE_ID.eq(caseId))
                .fetchOptional(r -> new SagaRow(r.getCaseId(), r.getApprovalRef(), r.getRequestId(), r.getOptionId(),
                        r.getStatus(), r.getAttempts(), r.getLastError(), r.getAppliedVersion()));
    }

    public void updateSaga(UUID caseId, String status, String error, Integer appliedVersion) {
        dsl.update(SAGA)
                .set(SAGA.STATUS, status)
                .set(SAGA.ATTEMPTS, SAGA.ATTEMPTS.plus(1))
                .set(SAGA.LAST_ERROR, error)
                .set(SAGA.APPLIED_VERSION, appliedVersion)
                .set(SAGA.UPDATED_AT, PgSql.now())
                .where(SAGA.CASE_ID.eq(caseId))
                .execute();
    }

    /** A stale approval ends the saga attempt; a later approval opens a new saga for the new snapshot. */
    public void deleteSaga(UUID caseId) {
        dsl.deleteFrom(SAGA).where(SAGA.CASE_ID.eq(caseId)).execute();
    }

    /** Returns false for a run already reported (at-least-once delivery from the gateway). */
    public boolean recordAgentOutcome(UUID runId, UUID caseId, UUID proposalId, boolean approvable, boolean shadow,
                                      List<String> failedChecks, String errorCode) {
        return dsl.insertInto(AGENT_OUTCOME)
                .set(AGENT_OUTCOME.RUN_ID, runId)
                .set(AGENT_OUTCOME.CASE_ID, caseId)
                .set(AGENT_OUTCOME.PROPOSAL_ID, proposalId)
                .set(AGENT_OUTCOME.APPROVABLE, approvable)
                .set(AGENT_OUTCOME.SHADOW, shadow)
                .set(AGENT_OUTCOME.FAILED_CHECKS, json.write(failedChecks))
                .set(AGENT_OUTCOME.ERROR_CODE, errorCode)
                .onConflictDoNothing()
                .execute() == 1;
    }

    public Optional<UUID> lastAgentRun(UUID caseId) {
        return dsl.select(AGENT_OUTCOME.RUN_ID).from(AGENT_OUTCOME).where(AGENT_OUTCOME.CASE_ID.eq(caseId))
                .orderBy(AGENT_OUTCOME.RECEIVED_AT.desc()).limit(1).fetchOptional(AGENT_OUTCOME.RUN_ID);
    }

    public void setSmsOutcome(String messageId, String outcome) {
        dsl.update(SMS_REPLY).set(SMS_REPLY.OUTCOME, outcome).where(SMS_REPLY.MESSAGE_ID.eq(messageId)).execute();
    }

    /** Returns false for a message already handled. */
    public boolean recordSmsReply(String messageId, UUID memberId, String keyword, UUID caseId, String outcome) {
        return dsl.insertInto(SMS_REPLY)
                .set(SMS_REPLY.MESSAGE_ID, messageId)
                .set(SMS_REPLY.MEMBER_ID, memberId)
                .set(SMS_REPLY.KEYWORD, keyword)
                .set(SMS_REPLY.CASE_ID, caseId)
                .set(SMS_REPLY.OUTCOME, outcome)
                .onConflictDoNothing()
                .execute() == 1;
    }
}
