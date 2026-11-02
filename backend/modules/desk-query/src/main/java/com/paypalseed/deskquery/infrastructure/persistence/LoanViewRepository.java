package com.paypalseed.deskquery.infrastructure.persistence;

import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.HARDSHIP_CASE_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.LOAN_INSTALMENT_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.LOAN_MISSED_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.LOAN_VIEW;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.LoanRow;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Total;
import com.paypalseed.deskquery.domain.model.AgingBuckets;
import com.paypalseed.deskquery.domain.model.ColumnType;
import com.paypalseed.deskquery.domain.model.GridQuery;
import com.paypalseed.deskquery.domain.model.NextDue;
import com.paypalseed.deskquery.domain.model.NextDue.Instalment;
import com.paypalseed.deskquery.infrastructure.persistence.KeysetPager.Column;
import com.paypalseed.platform.persistence.PgSql;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStep4;
import org.jooq.Record;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * The loan projection. Each group of columns has its own "as of" guard because the events that feed the groups are
 * independent facts about one loan: money (balance and status), delinquency, collection mode, and the schedule (by
 * version). A fact older than the guard changes nothing; an equal one rewrites the same values, so replays are harmless.
 */
@Repository
public class LoanViewRepository {

    private static final LocalDate NO_DUE_DATE = LocalDate.of(9999, 12, 31);

    /** A sentinel-free rank, so a grid sorts buckets by severity rather than by name. */
    private static final Field<Integer> BUCKET_RANK = DSL.field(rankSql(), Integer.class, LOAN_VIEW.AGING_BUCKET);

    private static final Map<String, Column> COLUMNS = Map.of(
            "disbursedOn", new Column(DSL.coalesce(LOAN_VIEW.DISBURSED_ON, LocalDate.EPOCH), ColumnType.DATE),
            "principalCents", new Column(DSL.coalesce(LOAN_VIEW.PRINCIPAL_CENTS, 0L), ColumnType.LONG),
            "outstandingCents", new Column(DSL.coalesce(LOAN_VIEW.OUTSTANDING_CENTS, 0L), ColumnType.LONG),
            "nextDueDate", new Column(DSL.coalesce(LOAN_VIEW.NEXT_DUE_DATE, NO_DUE_DATE), ColumnType.DATE),
            "daysLate", new Column(LOAN_VIEW.DAYS_LATE, ColumnType.INT),
            "agingBucket", new Column(BUCKET_RANK, ColumnType.INT),
            "status", new Column(LOAN_VIEW.STATUS, ColumnType.STRING));

    private static final List<SelectFieldOrAsterisk> PROJECTION = List.of(LOAN_VIEW.LOAN_ID, LOAN_VIEW.MEMBER_REF, LOAN_VIEW.STATUS,
            LOAN_VIEW.PRINCIPAL_CENTS, LOAN_VIEW.OUTSTANDING_CENTS, LOAN_VIEW.NEXT_DUE_DATE, LOAN_VIEW.NEXT_DUE_CENTS,
            LOAN_VIEW.DAYS_LATE, LOAN_VIEW.AGING_BUCKET, LOAN_VIEW.AUTOPAY, LOAN_VIEW.HARDSHIP_OPEN, LOAN_VIEW.DISBURSED_ON);

    private final DSLContext dsl;

    public LoanViewRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    private static String rankSql() {
        StringBuilder sql = new StringBuilder("case {0}");
        for (int i = 1; i < AgingBuckets.ORDER.size(); i++) {
            sql.append(" when '").append(AgingBuckets.ORDER.get(i)).append("' then ").append(i);
        }
        return sql.append(" else 0 end").toString();
    }

    /** Makes the row exist without claiming anything about the loan; the hardship flag is derived from the case view. */
    public void ensure(UUID loanId, UUID memberRef) {
        dsl.insertInto(LOAN_VIEW)
                .set(LOAN_VIEW.LOAN_ID, loanId)
                .set(LOAN_VIEW.MEMBER_REF, memberRef)
                .set(LOAN_VIEW.HARDSHIP_OPEN, DSL.field(openCase(loanId)))
                .onConflictDoNothing()
                .execute();
    }

    /** First sight of the loan: principal, disbursement date and, unless a money fact came first, the opening balance. */
    public void initialise(UUID loanId, long principalCents, LocalDate disbursedOn) {
        dsl.update(LOAN_VIEW)
                .set(LOAN_VIEW.PRINCIPAL_CENTS, principalCents)
                .set(LOAN_VIEW.DISBURSED_ON, disbursedOn)
                .set(LOAN_VIEW.OUTSTANDING_CENTS, DSL.coalesce(LOAN_VIEW.OUTSTANDING_CENTS, principalCents))
                .set(LOAN_VIEW.UPDATED_AT, PgSql.now())
                .where(LOAN_VIEW.LOAN_ID.eq(loanId).and(LOAN_VIEW.PRINCIPAL_CENTS.isNull()))
                .execute();
    }

    /** Adopts the schedule version if it is newer than the one held, replacing the instalment set. */
    public boolean applySchedule(UUID loanId, int version, List<Instalment> instalments) {
        int changed = dsl.update(LOAN_VIEW)
                .set(LOAN_VIEW.SCHEDULE_VERSION, version)
                .set(LOAN_VIEW.TERM_MONTHS, instalments.size())
                .set(LOAN_VIEW.UPDATED_AT, PgSql.now())
                .where(LOAN_VIEW.LOAN_ID.eq(loanId).and(LOAN_VIEW.SCHEDULE_VERSION.lt(version)))
                .execute();
        if (changed == 0) {
            return false;
        }
        dsl.deleteFrom(LOAN_INSTALMENT_VIEW).where(LOAN_INSTALMENT_VIEW.LOAN_ID.eq(loanId)).execute();
        if (!instalments.isEmpty()) {
            InsertValuesStep4<?, UUID, Integer, LocalDate, Long> insert = dsl.insertInto(LOAN_INSTALMENT_VIEW,
                    LOAN_INSTALMENT_VIEW.LOAN_ID, LOAN_INSTALMENT_VIEW.SEQ, LOAN_INSTALMENT_VIEW.DUE_DATE,
                    LOAN_INSTALMENT_VIEW.AMOUNT_CENTS);
            for (Instalment i : instalments) {
                insert = insert.values(loanId, i.seq(), i.dueDate(), i.amountCents());
            }
            insert.execute();
        }
        refreshNextDue(loanId);
        return true;
    }

    /** Balance and, when the event carries it, status. */
    public boolean recordMoney(UUID loanId, LocalDate asOf, long outstandingCents, String status) {
        Map<Field<?>, Object> set = new LinkedHashMap<>();
        set.put(LOAN_VIEW.OUTSTANDING_CENTS, outstandingCents);
        if (status != null) {
            set.put(LOAN_VIEW.STATUS, status);
        }
        return recordMoney(loanId, asOf, set);
    }

    public boolean recordRepaid(UUID loanId, LocalDate asOf, boolean onPlan) {
        Map<Field<?>, Object> set = new LinkedHashMap<>();
        set.put(LOAN_VIEW.STATUS, "REPAID");
        set.put(LOAN_VIEW.OUTSTANDING_CENTS, 0L);
        set.put(LOAN_VIEW.REPAID_ON, asOf);
        set.put(LOAN_VIEW.REPAID_ON_PLAN, onPlan);
        return recordMoney(loanId, asOf, set);
    }

    public boolean recordReopened(UUID loanId, LocalDate asOf, long outstandingCents) {
        Map<Field<?>, Object> set = new LinkedHashMap<>();
        set.put(LOAN_VIEW.STATUS, "ACTIVE");
        set.put(LOAN_VIEW.OUTSTANDING_CENTS, outstandingCents);
        set.put(LOAN_VIEW.REPAID_ON, null);
        set.put(LOAN_VIEW.REPAID_ON_PLAN, false);
        return recordMoney(loanId, asOf, set);
    }

    private boolean recordMoney(UUID loanId, LocalDate asOf, Map<Field<?>, Object> set) {
        set.put(LOAN_VIEW.MONEY_AS_OF, asOf);
        set.put(LOAN_VIEW.UPDATED_AT, PgSql.now());
        int changed = dsl.update(LOAN_VIEW).set(set)
                .where(LOAN_VIEW.LOAN_ID.eq(loanId).and(LOAN_VIEW.MONEY_AS_OF.isNull().or(LOAN_VIEW.MONEY_AS_OF.le(asOf))))
                .execute();
        if (changed == 1) {
            refreshNextDue(loanId);
        }
        return changed == 1;
    }

    public boolean recordDelinquency(UUID loanId, LocalDate asOf, String bucket, int daysLate) {
        return dsl.update(LOAN_VIEW)
                .set(LOAN_VIEW.AGING_BUCKET, bucket)
                .set(LOAN_VIEW.DAYS_LATE, daysLate)
                .set(LOAN_VIEW.BUCKET_AS_OF, asOf)
                .set(LOAN_VIEW.UPDATED_AT, PgSql.now())
                .where(LOAN_VIEW.LOAN_ID.eq(loanId).and(LOAN_VIEW.BUCKET_AS_OF.isNull().or(LOAN_VIEW.BUCKET_AS_OF.le(asOf))))
                .execute() == 1;
    }

    public boolean recordCollectionMode(UUID loanId, LocalDate asOf, boolean autopay) {
        return dsl.update(LOAN_VIEW)
                .set(LOAN_VIEW.AUTOPAY, autopay)
                .set(LOAN_VIEW.MODE_AS_OF, asOf)
                .set(LOAN_VIEW.UPDATED_AT, PgSql.now())
                .where(LOAN_VIEW.LOAN_ID.eq(loanId).and(LOAN_VIEW.MODE_AS_OF.isNull().or(LOAN_VIEW.MODE_AS_OF.le(asOf))))
                .execute() == 1;
    }

    /** Missed instalments are a set keyed by (schedule version, sequence), so a redelivery adds nothing. */
    public void recordMissed(UUID loanId, int version, int seq) {
        dsl.insertInto(LOAN_MISSED_VIEW)
                .set(LOAN_MISSED_VIEW.LOAN_ID, loanId)
                .set(LOAN_MISSED_VIEW.VERSION, version)
                .set(LOAN_MISSED_VIEW.SEQ, seq)
                .onConflictDoNothing()
                .execute();
        dsl.update(LOAN_VIEW)
                .set(LOAN_VIEW.MISSED_INSTALMENTS, DSL.field(DSL.selectCount().from(LOAN_MISSED_VIEW)
                        .where(LOAN_MISSED_VIEW.LOAN_ID.eq(loanId))))
                .set(LOAN_VIEW.UPDATED_AT, PgSql.now())
                .where(LOAN_VIEW.LOAN_ID.eq(loanId))
                .execute();
    }

    /** True when any hardship case on the loan is open. Recomputed from the case view, so its order of arrival is moot. */
    public void refreshHardshipFlag(UUID loanId) {
        dsl.update(LOAN_VIEW)
                .set(LOAN_VIEW.HARDSHIP_OPEN, DSL.field(openCase(loanId)))
                .set(LOAN_VIEW.UPDATED_AT, PgSql.now())
                .where(LOAN_VIEW.LOAN_ID.eq(loanId))
                .execute();
    }

    private static Condition openCase(UUID loanId) {
        return DSL.exists(DSL.selectOne().from(HARDSHIP_CASE_VIEW)
                .where(HARDSHIP_CASE_VIEW.LOAN_ID.eq(loanId).and(HARDSHIP_CASE_VIEW.STATE.eq("OPEN"))));
    }

    /** Works the next owing instalment out of the stored schedule and balance, whichever arrived last. */
    private void refreshNextDue(UUID loanId) {
        Long outstanding = dsl.select(LOAN_VIEW.OUTSTANDING_CENTS).from(LOAN_VIEW).where(LOAN_VIEW.LOAN_ID.eq(loanId))
                .fetchOne(LOAN_VIEW.OUTSTANDING_CENTS);
        List<Instalment> instalments = dsl.select(LOAN_INSTALMENT_VIEW.SEQ, LOAN_INSTALMENT_VIEW.DUE_DATE,
                        LOAN_INSTALMENT_VIEW.AMOUNT_CENTS)
                .from(LOAN_INSTALMENT_VIEW).where(LOAN_INSTALMENT_VIEW.LOAN_ID.eq(loanId)).orderBy(LOAN_INSTALMENT_VIEW.SEQ)
                .fetch(r -> new Instalment(r.value1(), r.value2(), r.value3()));
        Optional<NextDue> next = NextDue.of(instalments, outstanding);
        dsl.update(LOAN_VIEW)
                .set(LOAN_VIEW.NEXT_DUE_DATE, next.map(NextDue::dueDate).orElse(null))
                .set(LOAN_VIEW.NEXT_DUE_CENTS, next.map(NextDue::remainingCents).orElse(null))
                .where(LOAN_VIEW.LOAN_ID.eq(loanId))
                .execute();
    }

    public Keyset<LoanRow> page(GridQuery query) {
        return KeysetPager.fetch(dsl, LOAN_VIEW, where(query), PROJECTION, COLUMNS, LOAN_VIEW.LOAN_ID, query, LoanViewRepository::row);
    }

    public Total total(GridQuery query, int cap) {
        return KeysetPager.total(dsl, LOAN_VIEW, where(query), cap);
    }

    public Optional<java.time.Instant> asOf() {
        return Optional.ofNullable(dsl.select(DSL.max(LOAN_VIEW.UPDATED_AT)).from(LOAN_VIEW).fetchOne(0, java.time.Instant.class));
    }

    /** Only loans whose schedule has been seen are listed; a row created by an early money event is not a loan yet. */
    private static Condition where(GridQuery q) {
        Condition c = LOAN_VIEW.PRINCIPAL_CENTS.isNotNull();
        if (q.hasFilter("status")) {
            c = c.and(LOAN_VIEW.STATUS.in(q.values("status")));
        }
        if (q.hasFilter("agingBucket")) {
            c = c.and(LOAN_VIEW.AGING_BUCKET.in(q.values("agingBucket")));
        }
        if (q.flag("autopay") != null) {
            c = c.and(LOAN_VIEW.AUTOPAY.eq(q.flag("autopay")));
        }
        if (q.flag("hardship") != null) {
            c = c.and(LOAN_VIEW.HARDSHIP_OPEN.eq(q.flag("hardship")));
        }
        return c;
    }

    private static LoanRow row(Record r) {
        Long outstanding = r.get(LOAN_VIEW.OUTSTANDING_CENTS);
        return new LoanRow(r.get(LOAN_VIEW.LOAN_ID), r.get(LOAN_VIEW.MEMBER_REF), r.get(LOAN_VIEW.STATUS),
                r.get(LOAN_VIEW.PRINCIPAL_CENTS), outstanding == null ? 0 : outstanding, r.get(LOAN_VIEW.NEXT_DUE_DATE),
                r.get(LOAN_VIEW.NEXT_DUE_CENTS), r.get(LOAN_VIEW.DAYS_LATE), r.get(LOAN_VIEW.AGING_BUCKET),
                r.get(LOAN_VIEW.AUTOPAY), r.get(LOAN_VIEW.HARDSHIP_OPEN), r.get(LOAN_VIEW.DISBURSED_ON));
    }
}
