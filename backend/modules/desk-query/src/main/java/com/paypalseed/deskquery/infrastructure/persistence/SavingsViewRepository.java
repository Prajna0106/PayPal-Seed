package com.paypalseed.deskquery.infrastructure.persistence;

import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS_MONTH;

import com.paypalseed.platform.persistence.PgSql;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Saving history per member, kept as the set of months with a deposit so that order and duplicates do not matter. */
@Repository
public class SavingsViewRepository {

    private final DSLContext dsl;

    public SavingsViewRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void ensureMember(UUID memberRef) {
        dsl.insertInto(MEMBER_SAVINGS).set(MEMBER_SAVINGS.MEMBER_REF, memberRef).onConflictDoNothing().execute();
    }

    public void addMonth(UUID memberRef, LocalDate month) {
        dsl.insertInto(MEMBER_SAVINGS_MONTH)
                .set(MEMBER_SAVINGS_MONTH.MEMBER_REF, memberRef)
                .set(MEMBER_SAVINGS_MONTH.MONTH, month)
                .onConflictDoNothing()
                .execute();
    }

    public List<LocalDate> months(UUID memberRef) {
        return dsl.select(MEMBER_SAVINGS_MONTH.MONTH).from(MEMBER_SAVINGS_MONTH)
                .where(MEMBER_SAVINGS_MONTH.MEMBER_REF.eq(memberRef)).fetch(MEMBER_SAVINGS_MONTH.MONTH);
    }

    public void setStreak(UUID memberRef, int streakMonths) {
        dsl.insertInto(MEMBER_SAVINGS)
                .set(MEMBER_SAVINGS.MEMBER_REF, memberRef)
                .set(MEMBER_SAVINGS.STREAK_MONTHS, streakMonths)
                .onConflict(MEMBER_SAVINGS.MEMBER_REF).doUpdate()
                .set(MEMBER_SAVINGS.STREAK_MONTHS, streakMonths)
                .set(MEMBER_SAVINGS.UPDATED_AT, PgSql.now())
                .execute();
    }
}
