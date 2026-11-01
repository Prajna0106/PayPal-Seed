package com.paypalseed.deskquery.infrastructure.persistence;

import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.APPLICATION_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.ASSESSMENT_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.ApplicationRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Total;
import com.paypalseed.deskquery.domain.model.ColumnType;
import com.paypalseed.deskquery.domain.model.GridQuery;
import com.paypalseed.deskquery.domain.model.Grids;
import com.paypalseed.deskquery.domain.model.SlaBucket;
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
public class ApplicationViewRepository {

    /** The facts one application update carries; {@code sourceUpdatedAt} is the version the guard compares. */
    public record Fact(UUID applicationId, UUID memberRef, String status, long requestedAmountCents, int termMonths,
                       Instant submittedAt, Instant slaDueAt, String assignedOfficerRef, Instant sourceUpdatedAt) {
    }

    private static final Map<String, Column> COLUMNS = Map.of(
            "submittedAt", new Column(APPLICATION_VIEW.SUBMITTED_AT, ColumnType.INSTANT),
            "slaDueAt", new Column(APPLICATION_VIEW.SLA_DUE_AT, ColumnType.INSTANT),
            "requestedAmountCents", new Column(APPLICATION_VIEW.REQUESTED_AMOUNT_CENTS, ColumnType.LONG),
            "termMonths", new Column(APPLICATION_VIEW.TERM_MONTHS, ColumnType.INT),
            "savingStreakMonths", new Column(APPLICATION_VIEW.SAVING_STREAK_MONTHS, ColumnType.INT),
            "status", new Column(APPLICATION_VIEW.STATUS, ColumnType.STRING),
            "assessmentOutcome", new Column(DSL.coalesce(APPLICATION_VIEW.ASSESSMENT_OUTCOME, ""), ColumnType.STRING),
            "assignedOfficer", new Column(DSL.coalesce(APPLICATION_VIEW.ASSIGNED_OFFICER_REF, ""), ColumnType.STRING));

    private static final List<SelectFieldOrAsterisk> PROJECTION = List.of(APPLICATION_VIEW.APPLICATION_ID, APPLICATION_VIEW.MEMBER_REF,
            APPLICATION_VIEW.STATUS, APPLICATION_VIEW.REQUESTED_AMOUNT_CENTS, APPLICATION_VIEW.TERM_MONTHS,
            APPLICATION_VIEW.SAVING_STREAK_MONTHS, APPLICATION_VIEW.ASSESSMENT_OUTCOME, APPLICATION_VIEW.SUBMITTED_AT,
            APPLICATION_VIEW.SLA_DUE_AT, APPLICATION_VIEW.ASSIGNED_OFFICER_REF);

    private final DSLContext dsl;

    public ApplicationViewRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Inserts or advances an application. An older snapshot (an earlier {@code sourceUpdatedAt}) leaves the row alone, so
     * replays and reordering are harmless; an equal one rewrites the same values. The submission time and the SLA are
     * fixed by the first snapshot seen. Returns whether this snapshot is now the row's state.
     */
    public boolean upsert(Fact f) {
        return dsl.insertInto(APPLICATION_VIEW)
                .set(APPLICATION_VIEW.APPLICATION_ID, f.applicationId())
                .set(APPLICATION_VIEW.MEMBER_REF, f.memberRef())
                .set(APPLICATION_VIEW.STATUS, f.status())
                .set(APPLICATION_VIEW.REQUESTED_AMOUNT_CENTS, f.requestedAmountCents())
                .set(APPLICATION_VIEW.TERM_MONTHS, f.termMonths())
                .set(APPLICATION_VIEW.SAVING_STREAK_MONTHS, DSL.coalesce(DSL.field(DSL.select(MEMBER_SAVINGS.STREAK_MONTHS)
                        .from(MEMBER_SAVINGS).where(MEMBER_SAVINGS.MEMBER_REF.eq(f.memberRef()))), 0))
                .set(APPLICATION_VIEW.ASSESSMENT_OUTCOME, DSL.field(DSL.select(ASSESSMENT_VIEW.OUTCOME).from(ASSESSMENT_VIEW)
                        .where(ASSESSMENT_VIEW.APPLICATION_ID.eq(f.applicationId()))
                        .orderBy(ASSESSMENT_VIEW.CREATED_AT.desc()).limit(1)))
                .set(APPLICATION_VIEW.SUBMITTED_AT, f.submittedAt())
                .set(APPLICATION_VIEW.SLA_DUE_AT, f.slaDueAt())
                .set(APPLICATION_VIEW.ASSIGNED_OFFICER_REF, f.assignedOfficerRef())
                .set(APPLICATION_VIEW.SOURCE_UPDATED_AT, f.sourceUpdatedAt())
                .onConflict(APPLICATION_VIEW.APPLICATION_ID).doUpdate()
                .set(APPLICATION_VIEW.STATUS, f.status())
                .set(APPLICATION_VIEW.REQUESTED_AMOUNT_CENTS, f.requestedAmountCents())
                .set(APPLICATION_VIEW.TERM_MONTHS, f.termMonths())
                .set(APPLICATION_VIEW.ASSIGNED_OFFICER_REF, f.assignedOfficerRef())
                .set(APPLICATION_VIEW.SOURCE_UPDATED_AT, f.sourceUpdatedAt())
                .set(APPLICATION_VIEW.UPDATED_AT, PgSql.now())
                .where(APPLICATION_VIEW.SOURCE_UPDATED_AT.le(f.sourceUpdatedAt()))
                .execute() == 1;
    }

    /**
     * Serialises the projectors that read or write one member's application rows until the transaction ends. An
     * application, its fair check and the member's deposits arrive on separate threads; without this, one can read the
     * other's data just before it commits and leave a streak or an assessment outcome behind.
     */
    public void lockMember(UUID memberRef) {
        dsl.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", "deskquery-member:" + memberRef);
    }

    public void setAssessmentOutcome(UUID applicationId, String outcome) {
        dsl.update(APPLICATION_VIEW)
                .set(APPLICATION_VIEW.ASSESSMENT_OUTCOME, outcome)
                .set(APPLICATION_VIEW.UPDATED_AT, PgSql.now())
                .where(APPLICATION_VIEW.APPLICATION_ID.eq(applicationId))
                .execute();
    }

    public void setSavingStreak(UUID memberRef, int streakMonths) {
        dsl.update(APPLICATION_VIEW)
                .set(APPLICATION_VIEW.SAVING_STREAK_MONTHS, streakMonths)
                .set(APPLICATION_VIEW.UPDATED_AT, PgSql.now())
                .where(APPLICATION_VIEW.MEMBER_REF.eq(memberRef).and(APPLICATION_VIEW.SAVING_STREAK_MONTHS.ne(streakMonths)))
                .execute();
    }

    public Keyset<ApplicationRow> page(GridQuery query, Instant now, Duration dueSoon) {
        return KeysetPager.fetch(dsl, APPLICATION_VIEW, where(query, now), PROJECTION, COLUMNS, APPLICATION_VIEW.APPLICATION_ID,
                query, r -> row(r, now, dueSoon));
    }

    public Total total(GridQuery query, Instant now, int cap) {
        return KeysetPager.total(dsl, APPLICATION_VIEW, where(query, now), cap);
    }

    public Optional<Instant> asOf() {
        return Optional.ofNullable(dsl.select(DSL.max(APPLICATION_VIEW.UPDATED_AT)).from(APPLICATION_VIEW).fetchOne(0, Instant.class));
    }

    private static Condition where(GridQuery q, Instant now) {
        Condition c = DSL.trueCondition();
        if (q.hasFilter("status")) {
            c = c.and(APPLICATION_VIEW.STATUS.in(q.values("status")));
        }
        if (q.hasFilter("assessmentOutcome")) {
            c = c.and(APPLICATION_VIEW.ASSESSMENT_OUTCOME.in(q.values("assessmentOutcome")));
        }
        Boolean breached = q.flag("slaBreached");
        if (breached != null) {
            Condition overdue = APPLICATION_VIEW.STATUS.in(Grids.OPEN_APPLICATION_STATUSES).and(APPLICATION_VIEW.SLA_DUE_AT.le(now));
            c = c.and(breached ? overdue : overdue.not());
        }
        return c;
    }

    private static ApplicationRow row(Record r, Instant now, Duration dueSoon) {
        Instant submittedAt = r.get(APPLICATION_VIEW.SUBMITTED_AT);
        Instant slaDueAt = r.get(APPLICATION_VIEW.SLA_DUE_AT);
        String status = r.get(APPLICATION_VIEW.STATUS);
        boolean open = Grids.OPEN_APPLICATION_STATUSES.contains(status);
        SlaBucket sla = open ? SlaBucket.of(slaDueAt, now, dueSoon) : SlaBucket.NO_SLA;
        return new ApplicationRow(r.get(APPLICATION_VIEW.APPLICATION_ID), r.get(APPLICATION_VIEW.MEMBER_REF), status,
                r.get(APPLICATION_VIEW.REQUESTED_AMOUNT_CENTS), r.get(APPLICATION_VIEW.TERM_MONTHS),
                r.get(APPLICATION_VIEW.SAVING_STREAK_MONTHS), r.get(APPLICATION_VIEW.ASSESSMENT_OUTCOME), submittedAt,
                Math.max(0, Duration.between(submittedAt, now).toDays()), slaDueAt, sla.name(), sla == SlaBucket.OVERDUE,
                r.get(APPLICATION_VIEW.ASSIGNED_OFFICER_REF));
    }
}
