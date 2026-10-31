package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.COB_RUN;

import com.paypalseed.lending.infrastructure.persistence.jooq.tables.records.CobRunRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class CobRepository {

    public record CobRun(LocalDate businessDate, String status, String mode, int shardCount, int loansProcessed,
                         int loansFailed, String triggerRef) {
    }

    private final DSLContext dsl;

    public CobRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<CobRun> latest() {
        return dsl.selectFrom(COB_RUN).orderBy(COB_RUN.BUSINESS_DATE.desc()).limit(1).fetchOptional(CobRepository::map);
    }

    public Optional<CobRun> find(LocalDate date) {
        return dsl.selectFrom(COB_RUN).where(COB_RUN.BUSINESS_DATE.eq(date)).fetchOptional(CobRepository::map);
    }

    public List<CobRun> between(LocalDate from, LocalDate to) {
        return dsl.selectFrom(COB_RUN).where(COB_RUN.BUSINESS_DATE.between(from, to)).orderBy(COB_RUN.BUSINESS_DATE)
                .fetch(CobRepository::map);
    }

    /** Returns false if a run for the date already exists (idempotent start). */
    public boolean start(LocalDate date, String mode, int shardCount, String triggerRef) {
        return dsl.insertInto(COB_RUN)
                .set(COB_RUN.BUSINESS_DATE, date)
                .set(COB_RUN.STATUS, "RUNNING")
                .set(COB_RUN.MODE, mode)
                .set(COB_RUN.SHARD_COUNT, shardCount)
                .set(COB_RUN.TRIGGER_REF, triggerRef)
                .onConflict(COB_RUN.BUSINESS_DATE).doNothing()
                .execute() == 1;
    }

    public void addCounts(LocalDate date, int processed, int failed) {
        dsl.update(COB_RUN)
                .set(COB_RUN.LOANS_PROCESSED, COB_RUN.LOANS_PROCESSED.plus(processed))
                .set(COB_RUN.LOANS_FAILED, COB_RUN.LOANS_FAILED.plus(failed))
                .where(COB_RUN.BUSINESS_DATE.eq(date))
                .execute();
    }

    public void finish(LocalDate date, String status) {
        dsl.update(COB_RUN)
                .set(COB_RUN.STATUS, status)
                .set(COB_RUN.FINISHED_AT, PgSql.now())
                .where(COB_RUN.BUSINESS_DATE.eq(date))
                .execute();
    }

    private static CobRun map(CobRunRecord r) {
        return new CobRun(r.getBusinessDate(), r.getStatus(), r.getMode(), r.getShardCount(), r.getLoansProcessed(),
                r.getLoansFailed(), r.getTriggerRef());
    }
}
