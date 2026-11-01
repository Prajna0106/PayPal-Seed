package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.INSTALMENT;
import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.INSTALMENT_ALLOCATION;
import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.LOAN;

import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.infrastructure.persistence.jooq.tables.records.LoanRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class LoanRepository {

    private final DSLContext dsl;

    public LoanRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Inserts the loan; returns false when the disbursement was already registered. */
    public boolean insert(LoanRow loan, String disbursementRef) {
        return dsl.insertInto(LOAN)
                .set(LOAN.ID, loan.id())
                .set(LOAN.MEMBER_ID, loan.memberId())
                .set(LOAN.PRINCIPAL_CENTS, loan.principalCents())
                .set(LOAN.INSTALMENT_COUNT, loan.instalmentCount())
                .set(LOAN.DUE_DAY, loan.dueDay())
                .set(LOAN.DISBURSED_ON, loan.disbursedOn())
                .set(LOAN.DISBURSEMENT_REF, disbursementRef)
                .set(LOAN.POLICY_VERSION, loan.policyVersion())
                .set(LOAN.STATUS, loan.status().name())
                .set(LOAN.COLLECTION_MODE, loan.collectionMode().name())
                .set(LOAN.CURRENT_VERSION, 1)
                .set(LOAN.OUTSTANDING_CENTS, loan.principalCents())
                .set(LOAN.BUCKET, Bucket.CURRENT.name())
                .set(LOAN.BUCKET_SINCE, loan.disbursedOn())
                .set(LOAN.SHARD, LoanRow.shardOf(loan.id()))
                .onConflict(LOAN.DISBURSEMENT_REF).doNothing()
                .execute() == 1;
    }

    public Optional<UUID> idByDisbursementRef(String disbursementRef) {
        return dsl.select(LOAN.ID).from(LOAN).where(LOAN.DISBURSEMENT_REF.eq(disbursementRef)).fetchOptional(LOAN.ID);
    }

    public Optional<LoanRow> find(UUID id) {
        return dsl.selectFrom(LOAN).where(LOAN.ID.eq(id)).fetchOptional(LoanRepository::map);
    }

    /** Row lock for the rest of the transaction: every write path to a loan is serialised here. */
    public Optional<LoanRow> lock(UUID id) {
        return dsl.selectFrom(LOAN).where(LOAN.ID.eq(id)).forUpdate().fetchOptional(LoanRepository::map);
    }

    /** Close of business: skip a loan another transaction holds; the payment path re-ages it anyway. */
    public Optional<LoanRow> tryLock(UUID id) {
        return dsl.selectFrom(LOAN).where(LOAN.ID.eq(id)).forUpdate().skipLocked().fetchOptional(LoanRepository::map);
    }

    public List<LoanRow> byMember(UUID memberId) {
        return dsl.selectFrom(LOAN).where(LOAN.MEMBER_ID.eq(memberId)).orderBy(LOAN.DISBURSED_ON.desc())
                .fetch(LoanRepository::map);
    }

    public void updateDerived(UUID id, LoanStatus status, long outstanding, long overpaid, int dpd, Bucket bucket,
                              LocalDate bucketSince, LocalDate closedOn) {
        dsl.update(LOAN)
                .set(LOAN.STATUS, status.name())
                .set(LOAN.OUTSTANDING_CENTS, outstanding)
                .set(LOAN.OVERPAID_CENTS, overpaid)
                .set(LOAN.DPD, dpd)
                .set(LOAN.BUCKET, bucket.name())
                .set(LOAN.BUCKET_SINCE, bucketSince)
                .set(LOAN.CLOSED_ON, closedOn)
                .set(LOAN.ROW_VERSION, LOAN.ROW_VERSION.plus(1))
                .set(LOAN.UPDATED_AT, PgSql.now())
                .where(LOAN.ID.eq(id))
                .execute();
    }

    public void setCurrentVersion(UUID id, int version) {
        dsl.update(LOAN)
                .set(LOAN.CURRENT_VERSION, version)
                .set(LOAN.ROW_VERSION, LOAN.ROW_VERSION.plus(1))
                .set(LOAN.UPDATED_AT, PgSql.now())
                .where(LOAN.ID.eq(id))
                .execute();
    }

    public void setCollectionMode(UUID id, CollectionMode mode) {
        dsl.update(LOAN)
                .set(LOAN.COLLECTION_MODE, mode.name())
                .set(LOAN.ROW_VERSION, LOAN.ROW_VERSION.plus(1))
                .set(LOAN.UPDATED_AT, PgSql.now())
                .where(LOAN.ID.eq(id))
                .execute();
    }

    public void setLastClosedOn(UUID id, LocalDate date) {
        dsl.update(LOAN)
                .set(LOAN.LAST_CLOSED_ON, date)
                .where(LOAN.ID.eq(id).and(LOAN.LAST_CLOSED_ON.isNull().or(LOAN.LAST_CLOSED_ON.lt(date))))
                .execute();
    }

    /**
     * Scan mode: active loans of one shard group not yet closed for the date, in id order, starting after
     * {@code after} (null for the first page). Keyset pagination: every page costs the same however far
     * the run has got.
     */
    public List<UUID> activeNotClosed(LocalDate date, int shardGroup, int shardCount, int limit, UUID after) {
        Condition condition = LOAN.STATUS.eq(LoanStatus.ACTIVE.name())
                .and(LOAN.LAST_CLOSED_ON.isNull().or(LOAN.LAST_CLOSED_ON.lt(date)))
                .and(LOAN.SHARD.in(LoanRow.shardsOfGroup(shardGroup, shardCount)));
        if (after != null) {
            condition = condition.and(LOAN.ID.gt(after));
        }
        return dsl.select(LOAN.ID).from(LOAN).where(condition).orderBy(LOAN.ID).limit(limit).fetch(LOAN.ID);
    }

    /**
     * F5 scan work list: active loans of one shard group whose oldest owed instalment of the current version
     * is due between {@code from} and {@code to}, in id order after {@code after} (keyset paging).
     */
    public List<UUID> hardshipScanCandidates(LocalDate from, LocalDate to, int shardGroup, int shardCount, int limit, UUID after) {
        var oldestOwedDue = DSL.select(DSL.min(INSTALMENT.DUE_DATE))
                .from(INSTALMENT)
                .leftJoin(INSTALMENT_ALLOCATION).on(INSTALMENT_ALLOCATION.LOAN_ID.eq(INSTALMENT.LOAN_ID)
                        .and(INSTALMENT_ALLOCATION.VERSION.eq(INSTALMENT.VERSION))
                        .and(INSTALMENT_ALLOCATION.SEQ.eq(INSTALMENT.SEQ)))
                .where(INSTALMENT.LOAN_ID.eq(LOAN.ID)
                        .and(INSTALMENT.VERSION.eq(LOAN.CURRENT_VERSION))
                        .and(INSTALMENT.AMOUNT_CENTS.gt(DSL.coalesce(INSTALMENT_ALLOCATION.PAID_CENTS, 0L))))
                .asField();
        Condition condition = LOAN.STATUS.eq(LoanStatus.ACTIVE.name())
                .and(LOAN.SHARD.in(LoanRow.shardsOfGroup(shardGroup, shardCount)))
                .and(oldestOwedDue.between(from, to));
        if (after != null) {
            condition = condition.and(LOAN.ID.gt(after));
        }
        return dsl.select(LOAN.ID).from(LOAN).where(condition).orderBy(LOAN.ID).limit(limit).fetch(LOAN.ID);
    }

    public List<UUID> activeAutopayLoans() {
        return dsl.select(LOAN.ID).from(LOAN)
                .where(LOAN.STATUS.eq(LoanStatus.ACTIVE.name()).and(LOAN.COLLECTION_MODE.eq(CollectionMode.AUTOPAY.name())))
                .orderBy(LOAN.ID)
                .fetch(LOAN.ID);
    }

    private static LoanRow map(LoanRecord r) {
        return new LoanRow(
                r.getId(),
                r.getMemberId(),
                r.getPrincipalCents(),
                r.getInstalmentCount(),
                r.getDueDay(),
                r.getDisbursedOn(),
                r.getPolicyVersion(),
                LoanStatus.valueOf(r.getStatus()),
                CollectionMode.valueOf(r.getCollectionMode()),
                r.getCurrentVersion(),
                r.getOutstandingCents(),
                r.getOverpaidCents(),
                r.getDpd(),
                Bucket.valueOf(r.getBucket()),
                r.getBucketSince(),
                r.getLastClosedOn(),
                r.getClosedOn(),
                r.getRowVersion());
    }
}
