package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.LOAN_HARDSHIP_FLAG;
import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.LOAN_HOLD;

import com.paypalseed.lending.infrastructure.persistence.jooq.tables.records.LoanHoldRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Grace holds and the open-case flag that F5 hardship pushes into lending. */
@Repository
public class HardshipSupportRepository {

    public static final String CAUSE_SLA_ESCALATION = "SLA_ESCALATION";

    public record Hold(UUID id, UUID loanId, int version, int seq, LocalDate dueDate, LocalDate placedOn, LocalDate until,
                       String cause, String caseRef, LocalDate releasedOn) {

        /** The hold suppresses the miss on close-of-business date {@code asOf}. */
        public boolean coversOn(int currentVersion, LocalDate asOf) {
            return releasedOn == null && version == currentVersion && !asOf.isAfter(until);
        }
    }

    private final DSLContext dsl;

    public HardshipSupportRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Hold> unreleased(UUID loanId, int seq) {
        return dsl.selectFrom(LOAN_HOLD)
                .where(LOAN_HOLD.LOAN_ID.eq(loanId).and(LOAN_HOLD.SEQ.eq(seq)).and(LOAN_HOLD.RELEASED_ON.isNull()))
                .fetchOptional(HardshipSupportRepository::map);
    }

    public void insertHold(Hold h) {
        dsl.insertInto(LOAN_HOLD)
                .set(LOAN_HOLD.ID, h.id())
                .set(LOAN_HOLD.LOAN_ID, h.loanId())
                .set(LOAN_HOLD.VERSION, h.version())
                .set(LOAN_HOLD.SEQ, h.seq())
                .set(LOAN_HOLD.DUE_DATE, h.dueDate())
                .set(LOAN_HOLD.PLACED_ON, h.placedOn())
                .set(LOAN_HOLD.UNTIL, h.until())
                .set(LOAN_HOLD.CAUSE, h.cause())
                .set(LOAN_HOLD.CASE_REF, h.caseRef())
                .execute();
    }

    public void release(UUID holdId, LocalDate on, String reason) {
        dsl.update(LOAN_HOLD)
                .set(LOAN_HOLD.RELEASED_ON, on)
                .set(LOAN_HOLD.RELEASED_REASON, reason)
                .where(LOAN_HOLD.ID.eq(holdId).and(LOAN_HOLD.RELEASED_ON.isNull()))
                .execute();
    }

    /** Releases every unreleased hold of a case; returns the loans they were on. */
    public Set<UUID> releaseCase(String caseRef, LocalDate on, String reason) {
        return dsl.update(LOAN_HOLD)
                .set(LOAN_HOLD.RELEASED_ON, on)
                .set(LOAN_HOLD.RELEASED_REASON, reason)
                .where(LOAN_HOLD.CASE_REF.eq(caseRef).and(LOAN_HOLD.RELEASED_ON.isNull()))
                .returning(LOAN_HOLD.LOAN_ID)
                .fetch(LOAN_HOLD.LOAN_ID)
                .stream().collect(Collectors.toSet());
    }

    /** Every hold ever placed on a loan, released or not. */
    public List<Hold> allFor(UUID loanId) {
        return dsl.selectFrom(LOAN_HOLD).where(LOAN_HOLD.LOAN_ID.eq(loanId))
                .orderBy(LOAN_HOLD.CREATED_AT, LOAN_HOLD.ID)
                .fetch(HardshipSupportRepository::map);
    }

    public List<Hold> unreleasedFor(UUID loanId) {
        return dsl.selectFrom(LOAN_HOLD)
                .where(LOAN_HOLD.LOAN_ID.eq(loanId).and(LOAN_HOLD.RELEASED_ON.isNull()))
                .orderBy(LOAN_HOLD.SEQ)
                .fetch(HardshipSupportRepository::map);
    }

    public Optional<String> openCase(UUID loanId) {
        return dsl.select(LOAN_HARDSHIP_FLAG.CASE_REF).from(LOAN_HARDSHIP_FLAG)
                .where(LOAN_HARDSHIP_FLAG.LOAN_ID.eq(loanId))
                .fetchOptional(LOAN_HARDSHIP_FLAG.CASE_REF);
    }

    public void openFlag(UUID loanId, String caseRef, LocalDate since) {
        dsl.insertInto(LOAN_HARDSHIP_FLAG)
                .set(LOAN_HARDSHIP_FLAG.LOAN_ID, loanId)
                .set(LOAN_HARDSHIP_FLAG.CASE_REF, caseRef)
                .set(LOAN_HARDSHIP_FLAG.SINCE, since)
                .onConflict(LOAN_HARDSHIP_FLAG.LOAN_ID).doUpdate()
                .set(LOAN_HARDSHIP_FLAG.CASE_REF, caseRef)
                .set(LOAN_HARDSHIP_FLAG.SINCE, since)
                .execute();
    }

    /** Clears the flag only if this case set it; a newer case's flag stays. */
    public void closeFlag(UUID loanId, String caseRef) {
        dsl.deleteFrom(LOAN_HARDSHIP_FLAG)
                .where(LOAN_HARDSHIP_FLAG.LOAN_ID.eq(loanId).and(LOAN_HARDSHIP_FLAG.CASE_REF.eq(caseRef)))
                .execute();
    }

    private static Hold map(LoanHoldRecord r) {
        return new Hold(r.getId(), r.getLoanId(), r.getVersion(), r.getSeq(), r.getDueDate(), r.getPlacedOn(), r.getUntil(),
                r.getCause(), r.getCaseRef(), r.getReleasedOn());
    }
}
