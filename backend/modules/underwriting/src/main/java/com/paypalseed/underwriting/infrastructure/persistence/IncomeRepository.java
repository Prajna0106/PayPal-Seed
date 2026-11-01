package com.paypalseed.underwriting.infrastructure.persistence;

import static com.paypalseed.underwriting.infrastructure.persistence.jooq.Tables.INCOME_CONSENT;
import static com.paypalseed.underwriting.infrastructure.persistence.jooq.Tables.INCOME_WEEK;

import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IncomeRepository {

    public record Consent(boolean active, Integer usualPaydayDow) {
    }

    public record Week(LocalDate weekStart, long inflowCents) {
    }

    /**
     * When the weeks were last read from PayPal and the business date their buckets were cut for. Both are null
     * for weeks that were seeded by hand.
     */
    public record RefreshState(Instant refreshedAt, LocalDate anchor) {
    }

    private final DSLContext dsl;

    public IncomeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Consent> consent(UUID memberId) {
        return dsl.select(INCOME_CONSENT.REVOKED_AT, INCOME_CONSENT.USUAL_PAYDAY_DOW).from(INCOME_CONSENT)
                .where(INCOME_CONSENT.MEMBER_ID.eq(memberId))
                .fetchOptional(r -> new Consent(r.value1() == null, r.value2()));
    }

    public void grant(UUID memberId, Instant at, Integer usualPaydayDow) {
        dsl.insertInto(INCOME_CONSENT)
                .set(INCOME_CONSENT.MEMBER_ID, memberId)
                .set(INCOME_CONSENT.GRANTED_AT, at)
                .set(INCOME_CONSENT.USUAL_PAYDAY_DOW, usualPaydayDow)
                .onConflict(INCOME_CONSENT.MEMBER_ID).doUpdate()
                .set(INCOME_CONSENT.GRANTED_AT, at)
                .set(INCOME_CONSENT.REVOKED_AT, DSL.castNull(INCOME_CONSENT.REVOKED_AT))
                .set(INCOME_CONSENT.USUAL_PAYDAY_DOW, usualPaydayDow)
                .set(INCOME_CONSENT.INCOME_REFRESHED_AT, DSL.castNull(INCOME_CONSENT.INCOME_REFRESHED_AT))
                .set(INCOME_CONSENT.INCOME_ANCHOR, DSL.castNull(INCOME_CONSENT.INCOME_ANCHOR))
                .set(INCOME_CONSENT.UPDATED_AT, PgSql.now())
                .execute();
    }

    public void revoke(UUID memberId, Instant at) {
        dsl.update(INCOME_CONSENT)
                .set(INCOME_CONSENT.REVOKED_AT, at)
                .set(INCOME_CONSENT.UPDATED_AT, PgSql.now())
                .where(INCOME_CONSENT.MEMBER_ID.eq(memberId).and(INCOME_CONSENT.REVOKED_AT.isNull()))
                .execute();
    }

    public void upsertWeeks(UUID memberId, List<Week> weeks) {
        if (weeks.isEmpty()) {
            return;
        }
        var rows = dsl.insertInto(INCOME_WEEK, INCOME_WEEK.MEMBER_ID, INCOME_WEEK.WEEK_START, INCOME_WEEK.INFLOW_CENTS);
        for (Week week : weeks) {
            rows = rows.values(memberId, week.weekStart(), week.inflowCents());
        }
        rows.onConflict(INCOME_WEEK.MEMBER_ID, INCOME_WEEK.WEEK_START).doUpdate()
                .set(INCOME_WEEK.INFLOW_CENTS, DSL.excluded(INCOME_WEEK.INFLOW_CENTS))
                .execute();
    }

    /** Weekly totals whose week starts in {@code [from, to]}, oldest first. */
    public List<Week> weeks(UUID memberId, LocalDate from, LocalDate to) {
        return dsl.select(INCOME_WEEK.WEEK_START, INCOME_WEEK.INFLOW_CENTS).from(INCOME_WEEK)
                .where(INCOME_WEEK.MEMBER_ID.eq(memberId).and(INCOME_WEEK.WEEK_START.between(from, to)))
                .orderBy(INCOME_WEEK.WEEK_START)
                .fetch(r -> new Week(r.value1(), r.value2()));
    }

    public Optional<RefreshState> refreshState(UUID memberId) {
        return dsl.select(INCOME_CONSENT.INCOME_REFRESHED_AT, INCOME_CONSENT.INCOME_ANCHOR).from(INCOME_CONSENT)
                .where(INCOME_CONSENT.MEMBER_ID.eq(memberId))
                .fetchOptional(r -> new RefreshState(r.value1(), r.value2()));
    }

    /**
     * Replaces every stored week with the freshly read ones and records when and for which date. One transaction, so
     * a reader sees the old summary or the new one, never a mix of two alignments (which would count a day twice).
     */
    @Transactional
    public void replaceFetchedWeeks(UUID memberId, List<Week> weeks, LocalDate anchor, Instant at) {
        dsl.deleteFrom(INCOME_WEEK).where(INCOME_WEEK.MEMBER_ID.eq(memberId)).execute();
        upsertWeeks(memberId, weeks);
        dsl.update(INCOME_CONSENT)
                .set(INCOME_CONSENT.INCOME_REFRESHED_AT, at)
                .set(INCOME_CONSENT.INCOME_ANCHOR, anchor)
                .set(INCOME_CONSENT.UPDATED_AT, PgSql.now())
                .where(INCOME_CONSENT.MEMBER_ID.eq(memberId))
                .execute();
    }

    /** Forgets what was read from PayPal for a member who withdrew the consent. Safe to repeat. */
    @Transactional
    public void purgeFetched(UUID memberId) {
        dsl.deleteFrom(INCOME_WEEK).where(INCOME_WEEK.MEMBER_ID.eq(memberId)).execute();
        dsl.update(INCOME_CONSENT)
                .set(INCOME_CONSENT.INCOME_REFRESHED_AT, DSL.castNull(INCOME_CONSENT.INCOME_REFRESHED_AT))
                .set(INCOME_CONSENT.INCOME_ANCHOR, DSL.castNull(INCOME_CONSENT.INCOME_ANCHOR))
                .where(INCOME_CONSENT.MEMBER_ID.eq(memberId))
                .execute();
    }
}
