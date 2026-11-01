package com.paypalseed.deskquery.infrastructure.persistence;

import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.ASSESSMENT_VIEW;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.AssessmentRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Total;
import com.paypalseed.deskquery.domain.model.ColumnType;
import com.paypalseed.deskquery.domain.model.GridQuery;
import com.paypalseed.deskquery.infrastructure.persistence.KeysetPager.Column;
import com.paypalseed.platform.persistence.PgSql;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class AssessmentViewRepository {

    /** One fair-check snapshot. {@code sourceUpdatedAt} is the decision time when decided, else the creation time. */
    public record Fact(UUID assessmentId, UUID memberRef, UUID applicationId, boolean recommended,
                       long recommendedAmountCents, String status, String outcome, boolean disputed, Instant createdAt,
                       Instant sourceUpdatedAt) {
    }

    private static final Map<String, Column> COLUMNS = Map.of(
            "createdAt", new Column(ASSESSMENT_VIEW.CREATED_AT, ColumnType.INSTANT),
            "recommendedAmountCents", new Column(ASSESSMENT_VIEW.RECOMMENDED_AMOUNT_CENTS, ColumnType.LONG),
            "status", new Column(ASSESSMENT_VIEW.STATUS, ColumnType.STRING));

    private static final List<SelectFieldOrAsterisk> PROJECTION = List.of(ASSESSMENT_VIEW.ASSESSMENT_ID, ASSESSMENT_VIEW.MEMBER_REF,
            ASSESSMENT_VIEW.APPLICATION_ID, ASSESSMENT_VIEW.RECOMMENDED, ASSESSMENT_VIEW.RECOMMENDED_AMOUNT_CENTS,
            ASSESSMENT_VIEW.STATUS, ASSESSMENT_VIEW.DISPUTED, ASSESSMENT_VIEW.CREATED_AT);

    private final DSLContext dsl;

    public AssessmentViewRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Inserts or advances an assessment; an older snapshot changes nothing. A dispute is sticky: once any snapshot says the
     * member disputed, a later or replayed snapshot cannot clear it, because a dispute raised carries no timestamp of its
     * own. Returns whether this snapshot is now the row's state.
     */
    public boolean upsert(Fact f) {
        return dsl.insertInto(ASSESSMENT_VIEW)
                .set(ASSESSMENT_VIEW.ASSESSMENT_ID, f.assessmentId())
                .set(ASSESSMENT_VIEW.MEMBER_REF, f.memberRef())
                .set(ASSESSMENT_VIEW.APPLICATION_ID, f.applicationId())
                .set(ASSESSMENT_VIEW.RECOMMENDED, f.recommended())
                .set(ASSESSMENT_VIEW.RECOMMENDED_AMOUNT_CENTS, f.recommendedAmountCents())
                .set(ASSESSMENT_VIEW.STATUS, f.status())
                .set(ASSESSMENT_VIEW.OUTCOME, f.outcome())
                .set(ASSESSMENT_VIEW.DISPUTED, f.disputed())
                .set(ASSESSMENT_VIEW.CREATED_AT, f.createdAt())
                .set(ASSESSMENT_VIEW.SOURCE_UPDATED_AT, f.sourceUpdatedAt())
                .onConflict(ASSESSMENT_VIEW.ASSESSMENT_ID).doUpdate()
                .set(ASSESSMENT_VIEW.RECOMMENDED, f.recommended())
                .set(ASSESSMENT_VIEW.RECOMMENDED_AMOUNT_CENTS, f.recommendedAmountCents())
                .set(ASSESSMENT_VIEW.STATUS, f.status())
                .set(ASSESSMENT_VIEW.OUTCOME, f.outcome())
                .set(ASSESSMENT_VIEW.DISPUTED, DSL.field(ASSESSMENT_VIEW.DISPUTED.isTrue().or(DSL.condition(f.disputed()))))
                .set(ASSESSMENT_VIEW.SOURCE_UPDATED_AT, f.sourceUpdatedAt())
                .set(ASSESSMENT_VIEW.UPDATED_AT, PgSql.now())
                .where(ASSESSMENT_VIEW.SOURCE_UPDATED_AT.le(f.sourceUpdatedAt()))
                .execute() == 1;
    }

    /** Keeps the dispute flag when a snapshot older than the row arrives carrying a dispute. */
    public void markDisputed(UUID assessmentId) {
        dsl.update(ASSESSMENT_VIEW)
                .set(ASSESSMENT_VIEW.DISPUTED, true)
                .set(ASSESSMENT_VIEW.UPDATED_AT, PgSql.now())
                .where(ASSESSMENT_VIEW.ASSESSMENT_ID.eq(assessmentId).and(ASSESSMENT_VIEW.DISPUTED.isFalse()))
                .execute();
    }

    public Keyset<AssessmentRow> page(GridQuery query, Instant now) {
        return KeysetPager.fetch(dsl, ASSESSMENT_VIEW, where(query), PROJECTION, COLUMNS, ASSESSMENT_VIEW.ASSESSMENT_ID, query,
                r -> row(r, now));
    }

    public Total total(GridQuery query, int cap) {
        return KeysetPager.total(dsl, ASSESSMENT_VIEW, where(query), cap);
    }

    public Optional<Instant> asOf() {
        return Optional.ofNullable(dsl.select(DSL.max(ASSESSMENT_VIEW.UPDATED_AT)).from(ASSESSMENT_VIEW).fetchOne(0, Instant.class));
    }

    private static Condition where(GridQuery q) {
        Condition c = DSL.trueCondition();
        if (q.hasFilter("status")) {
            c = c.and(ASSESSMENT_VIEW.STATUS.in(q.values("status")));
        }
        if (q.flag("disputed") != null) {
            c = c.and(ASSESSMENT_VIEW.DISPUTED.eq(q.flag("disputed")));
        }
        if (q.flag("recommended") != null) {
            c = c.and(ASSESSMENT_VIEW.RECOMMENDED.eq(q.flag("recommended")));
        }
        return c;
    }

    private static AssessmentRow row(Record r, Instant now) {
        Instant createdAt = r.get(ASSESSMENT_VIEW.CREATED_AT);
        return new AssessmentRow(r.get(ASSESSMENT_VIEW.ASSESSMENT_ID), r.get(ASSESSMENT_VIEW.MEMBER_REF),
                r.get(ASSESSMENT_VIEW.APPLICATION_ID), r.get(ASSESSMENT_VIEW.RECOMMENDED),
                r.get(ASSESSMENT_VIEW.RECOMMENDED_AMOUNT_CENTS), r.get(ASSESSMENT_VIEW.STATUS), r.get(ASSESSMENT_VIEW.DISPUTED),
                createdAt, Math.max(0, Duration.between(createdAt, now).toDays()));
    }
}
