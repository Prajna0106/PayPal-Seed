package com.paypalseed.fraud.infrastructure.persistence;

import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.FLAG;

import com.paypalseed.fraud.api.dto.FraudDtos.FlagStatus;
import com.paypalseed.fraud.infrastructure.persistence.jooq.tables.records.FlagRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Flags: the advice an officer reviews. */
@Repository
public class FlagRepository {

    public record FlagRow(UUID id, UUID memberId, FlagStatus status, int score, Instant raisedAt, Instant reviewedAt) {
    }

    private final DSLContext dsl;

    FlagRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(UUID id, UUID memberId, int score, Instant raisedAt) {
        dsl.insertInto(FLAG)
                .set(FLAG.ID, id)
                .set(FLAG.MEMBER_ID, memberId)
                .set(FLAG.STATUS, FlagStatus.OPEN.name())
                .set(FLAG.SCORE, score)
                .set(FLAG.RAISED_AT, raisedAt)
                .execute();
    }

    public Optional<FlagRow> openFor(UUID memberId) {
        return dsl.selectFrom(FLAG).where(FLAG.MEMBER_ID.eq(memberId).and(FLAG.STATUS.eq(FlagStatus.OPEN.name())))
                .fetchOptional(FlagRepository::row);
    }

    /** The open flag if there is one, otherwise the newest reviewed one. */
    public Optional<FlagRow> latestFor(UUID memberId) {
        Optional<FlagRow> open = openFor(memberId);
        if (open.isPresent()) {
            return open;
        }
        return dsl.selectFrom(FLAG).where(FLAG.MEMBER_ID.eq(memberId)).orderBy(FLAG.ID.desc()).limit(1)
                .fetchOptional(FlagRepository::row);
    }

    public Optional<FlagRow> find(UUID id) {
        return dsl.selectFrom(FLAG).where(FLAG.ID.eq(id)).fetchOptional(FlagRepository::row);
    }

    public Optional<FlagRow> lock(UUID id) {
        return dsl.selectFrom(FLAG).where(FLAG.ID.eq(id)).forUpdate().fetchOptional(FlagRepository::row);
    }

    public void setScore(UUID id, int score) {
        dsl.update(FLAG).set(FLAG.SCORE, score).set(FLAG.UPDATED_AT, PgSql.now()).where(FLAG.ID.eq(id)).execute();
    }

    public void markReviewed(UUID id, FlagStatus status, Instant at) {
        dsl.update(FLAG).set(FLAG.STATUS, status.name()).set(FLAG.REVIEWED_AT, at).set(FLAG.UPDATED_AT, PgSql.now())
                .where(FLAG.ID.eq(id).and(FLAG.STATUS.eq(FlagStatus.OPEN.name()))).execute();
    }

    /** Keyset page, newest first: ids are time-ordered, so {@code before} is the previous page's last id. */
    public List<FlagRow> page(List<FlagStatus> statuses, UUID before, int limit) {
        Condition where = DSL.trueCondition();
        if (!statuses.isEmpty()) {
            where = where.and(FLAG.STATUS.in(statuses.stream().map(Enum::name).toList()));
        }
        if (before != null) {
            where = where.and(FLAG.ID.lt(before));
        }
        return dsl.selectFrom(FLAG).where(where).orderBy(FLAG.ID.desc()).limit(limit).fetch(FlagRepository::row);
    }

    private static FlagRow row(FlagRecord r) {
        return new FlagRow(r.getId(), r.getMemberId(), FlagStatus.valueOf(r.getStatus()), r.getScore(), r.getRaisedAt(),
                r.getReviewedAt());
    }
}
