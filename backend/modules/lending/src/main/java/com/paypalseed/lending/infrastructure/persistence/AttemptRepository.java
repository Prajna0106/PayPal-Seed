package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.COLLECTION_ATTEMPT;

import com.paypalseed.lending.infrastructure.persistence.jooq.tables.records.CollectionAttemptRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Autopay collection attempts: the scheduled charge and its single retry. */
@Repository
public class AttemptRepository {

    public enum Status {
        PLANNED,
        REQUESTED,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public record Attempt(UUID id, UUID loanId, int version, int seq, int attemptNo, String kind, LocalDate plannedOn,
                          Status status, String reason) {
    }

    private final DSLContext dsl;

    public AttemptRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(Attempt a) {
        dsl.insertInto(COLLECTION_ATTEMPT)
                .set(COLLECTION_ATTEMPT.ID, a.id())
                .set(COLLECTION_ATTEMPT.LOAN_ID, a.loanId())
                .set(COLLECTION_ATTEMPT.VERSION, a.version())
                .set(COLLECTION_ATTEMPT.SEQ, a.seq())
                .set(COLLECTION_ATTEMPT.ATTEMPT_NO, a.attemptNo())
                .set(COLLECTION_ATTEMPT.KIND, a.kind())
                .set(COLLECTION_ATTEMPT.PLANNED_ON, a.plannedOn())
                .set(COLLECTION_ATTEMPT.STATUS, a.status().name())
                .set(COLLECTION_ATTEMPT.REASON, a.reason())
                .onConflict(COLLECTION_ATTEMPT.LOAN_ID, COLLECTION_ATTEMPT.VERSION, COLLECTION_ATTEMPT.SEQ,
                        COLLECTION_ATTEMPT.ATTEMPT_NO)
                .doNothing()
                .execute();
    }

    public List<Attempt> forInstalment(UUID loanId, int version, int seq) {
        return select(COLLECTION_ATTEMPT.LOAN_ID.eq(loanId).and(COLLECTION_ATTEMPT.VERSION.eq(version))
                .and(COLLECTION_ATTEMPT.SEQ.eq(seq)), COLLECTION_ATTEMPT.ATTEMPT_NO);
    }

    public List<Attempt> planned(UUID loanId) {
        return select(COLLECTION_ATTEMPT.LOAN_ID.eq(loanId).and(COLLECTION_ATTEMPT.STATUS.eq(Status.PLANNED.name())),
                COLLECTION_ATTEMPT.PLANNED_ON);
    }

    /** Attempts still waiting on an outcome: planned retries and requested captures. */
    public List<Attempt> open(UUID loanId) {
        return select(COLLECTION_ATTEMPT.LOAN_ID.eq(loanId)
                .and(COLLECTION_ATTEMPT.STATUS.in(Status.PLANNED.name(), Status.REQUESTED.name())),
                COLLECTION_ATTEMPT.PLANNED_ON);
    }

    /** Charges that failed on or after {@code from} (scheduled charges and retries). */
    public int failedSince(UUID loanId, LocalDate from) {
        return dsl.fetchCount(COLLECTION_ATTEMPT, COLLECTION_ATTEMPT.LOAN_ID.eq(loanId)
                .and(COLLECTION_ATTEMPT.STATUS.eq(Status.FAILED.name()))
                .and(COLLECTION_ATTEMPT.PLANNED_ON.ge(from)));
    }

    public Optional<Attempt> find(UUID id) {
        return dsl.selectFrom(COLLECTION_ATTEMPT).where(COLLECTION_ATTEMPT.ID.eq(id))
                .fetchOptional(AttemptRepository::map);
    }

    public void setStatus(UUID id, Status status, String reason) {
        dsl.update(COLLECTION_ATTEMPT)
                .set(COLLECTION_ATTEMPT.STATUS, status.name())
                .set(COLLECTION_ATTEMPT.REASON, DSL.coalesce(DSL.val(reason, COLLECTION_ATTEMPT.REASON), COLLECTION_ATTEMPT.REASON))
                .set(COLLECTION_ATTEMPT.UPDATED_AT, PgSql.now())
                .where(COLLECTION_ATTEMPT.ID.eq(id))
                .execute();
    }

    /** Money arrived another way: planned retries are no longer needed. */
    public int cancelPlanned(UUID loanId, String reason) {
        return dsl.update(COLLECTION_ATTEMPT)
                .set(COLLECTION_ATTEMPT.STATUS, Status.CANCELLED.name())
                .set(COLLECTION_ATTEMPT.REASON, reason)
                .set(COLLECTION_ATTEMPT.UPDATED_AT, PgSql.now())
                .where(COLLECTION_ATTEMPT.LOAN_ID.eq(loanId).and(COLLECTION_ATTEMPT.STATUS.eq(Status.PLANNED.name())))
                .execute();
    }

    private List<Attempt> select(Condition where, org.jooq.OrderField<?> orderBy) {
        return dsl.selectFrom(COLLECTION_ATTEMPT).where(where).orderBy(orderBy).fetch(AttemptRepository::map);
    }

    private static Attempt map(CollectionAttemptRecord r) {
        return new Attempt(r.getId(), r.getLoanId(), r.getVersion(), r.getSeq(), r.getAttemptNo(), r.getKind(),
                r.getPlannedOn(), Status.valueOf(r.getStatus()), r.getReason());
    }
}
