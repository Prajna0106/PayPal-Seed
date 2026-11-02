package com.paypalseed.creditrecord.infrastructure.persistence;

import static com.paypalseed.creditrecord.infrastructure.persistence.jooq.Tables.MEMBER_RECORD;
import static com.paypalseed.creditrecord.infrastructure.persistence.jooq.Tables.RECORD_FACT;

import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.application.ports.RecordStore;
import com.paypalseed.creditrecord.domain.model.RecordState;
import com.paypalseed.creditrecord.infrastructure.persistence.jooq.tables.records.MemberRecordRecord;
import com.paypalseed.engines.rules.record.RecordFacts;
import com.paypalseed.engines.rules.record.RecordLevel;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** The append-only fact store and the per-member state row. */
@Repository
public class RecordRepository implements RecordStore {

    private final DSLContext dsl;

    RecordRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void lock(UUID memberId) {
        MemberLock.acquire(dsl, memberId);
    }

    @Override
    public boolean append(RecordFact fact) {
        return dsl.insertInto(RECORD_FACT)
                .set(RECORD_FACT.FACT_KEY, fact.factKey())
                .set(RECORD_FACT.MEMBER_ID, fact.memberId())
                .set(RECORD_FACT.KIND, fact.kind().name())
                .set(RECORD_FACT.OCCURRED_ON, fact.occurredOn())
                .set(RECORD_FACT.LOAN_ID, fact.loanId())
                .set(RECORD_FACT.INST_SEQ, fact.seq())
                .set(RECORD_FACT.DAYS_LATE, fact.daysLate())
                .set(RECORD_FACT.MISSED_COUNT, fact.missedCount())
                .set(RECORD_FACT.CASE_ID, fact.caseId())
                .onConflict(RECORD_FACT.FACT_KEY).doNothing()
                .execute() == 1;
    }

    @Override
    public Optional<RecordState> find(UUID memberId) {
        return dsl.selectFrom(MEMBER_RECORD).where(MEMBER_RECORD.MEMBER_ID.eq(memberId)).fetchOptional(RecordRepository::map);
    }

    @Override
    public void save(RecordState s) {
        MemberRecordRecord r = dsl.newRecord(MEMBER_RECORD);
        r.setMemberId(s.memberId());
        r.setLevel(s.level().rank());
        r.setSince(s.since());
        r.setLevelSince(s.levelSince());
        r.setLifeSavingOnTime(s.lifetime().onTimeSavingMonths());
        r.setLifeSavingMissed(s.lifetime().missedSavingMonths());
        r.setLifeLoansOnPlan(s.lifetime().loansRepaidOnPlan());
        r.setLifeLoansOffPlan(s.lifetime().loansRepaidOffPlan());
        r.setLifeInstOnTime(s.lifetime().instalmentsOnTime());
        r.setLifeInstLate(s.lifetime().instalmentsLate());
        r.setCurSavingOnTime(s.current().onTimeSavingMonths());
        r.setCurSavingMissed(s.current().missedSavingMonths());
        r.setCurLoansOnPlan(s.current().loansRepaidOnPlan());
        r.setCurLoansOffPlan(s.current().loansRepaidOffPlan());
        r.setCurInstOnTime(s.current().instalmentsOnTime());
        r.setCurInstLate(s.current().instalmentsLate());
        r.setLoansDisbursed(s.loansDisbursed());
        r.setHardshipPlans(s.hardshipPlans());
        r.setDefaults(s.defaults());
        r.setLastSavingMonth(s.lastSavingMonth());
        r.setSavingAccountedThrough(s.savingAccountedThrough());
        r.setMissedStreak(s.missedStreak());
        r.setOpenLate(sorted(s.openLate()));
        r.setSettled(sorted(s.settled()));
        r.setFactCount(s.factCount());
        dsl.insertInto(MEMBER_RECORD).set(r)
                .onConflict(MEMBER_RECORD.MEMBER_ID).doUpdate().set(r)
                .set(MEMBER_RECORD.UPDATED_AT, DSL.field("now()", MEMBER_RECORD.UPDATED_AT.getDataType()))
                .execute();
    }

    @Override
    public List<StoredFact> history(UUID memberId, Long before, int limit) {
        var where = RECORD_FACT.MEMBER_ID.eq(memberId);
        if (before != null) {
            where = where.and(RECORD_FACT.SEQ.lt(before));
        }
        return dsl.selectFrom(RECORD_FACT).where(where).orderBy(RECORD_FACT.SEQ.desc()).limit(limit)
                .fetch(f -> new StoredFact(f.getSeq(), RecordFact.Kind.valueOf(f.getKind()), f.getOccurredOn(), f.getDaysLate()));
    }

    @Override
    public List<UUID> membersToClose(LocalDate throughMonth, int limit) {
        return dsl.select(MEMBER_RECORD.MEMBER_ID).from(MEMBER_RECORD)
                .where(MEMBER_RECORD.SAVING_ACCOUNTED_THROUGH.lt(throughMonth))
                .orderBy(MEMBER_RECORD.SAVING_ACCOUNTED_THROUGH, MEMBER_RECORD.MEMBER_ID)
                .limit(limit)
                .fetch(MEMBER_RECORD.MEMBER_ID);
    }

    private static String[] sorted(Set<String> keys) {
        return keys.stream().sorted().toArray(String[]::new);
    }

    private static RecordState map(MemberRecordRecord r) {
        RecordFacts lifetime = new RecordFacts(r.getLifeSavingOnTime(), r.getLifeSavingMissed(), r.getLifeLoansOnPlan(),
                r.getLifeLoansOffPlan(), r.getLifeInstOnTime(), r.getLifeInstLate(), r.getOpenLate().length);
        RecordFacts current = new RecordFacts(r.getCurSavingOnTime(), r.getCurSavingMissed(), r.getCurLoansOnPlan(),
                r.getCurLoansOffPlan(), r.getCurInstOnTime(), r.getCurInstLate(), r.getOpenLate().length);
        return new RecordState(r.getMemberId(), RecordLevel.ofRank(r.getLevel()), r.getSince(), r.getLevelSince(), lifetime,
                current, r.getLoansDisbursed(), r.getHardshipPlans(), r.getDefaults(), r.getLastSavingMonth(),
                r.getSavingAccountedThrough(), r.getMissedStreak(), Set.copyOf(Arrays.asList(r.getOpenLate())),
                Set.copyOf(Arrays.asList(r.getSettled())), r.getFactCount());
    }
}
