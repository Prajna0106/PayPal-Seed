package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.HELD_REFUND;

import com.paypalseed.lending.infrastructure.persistence.jooq.tables.records.HeldRefundRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Refunds owed to members for money held on loans that never stood (ADR 0024, closing pass). */
@Repository
public class HeldRefundRepository {

    public enum Status {
        PENDING,
        REFUNDED,
        FAILED
    }

    public record HeldRefund(String reference, UUID loanId, UUID memberId, long amountCents, Status status, int attempts,
                             String lastError, Instant nextAttemptAt, String refundIds) {
    }

    private final DSLContext dsl;

    public HeldRefundRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Records the refund owed; the same reference twice is the same refund. @return true when it was new */
    public boolean insertIfAbsent(String reference, UUID loanId, UUID memberId, long amountCents, Instant dueAt) {
        return dsl.insertInto(HELD_REFUND)
                .set(HELD_REFUND.REFERENCE, reference)
                .set(HELD_REFUND.LOAN_ID, loanId)
                .set(HELD_REFUND.MEMBER_ID, memberId)
                .set(HELD_REFUND.AMOUNT_CENTS, amountCents)
                .set(HELD_REFUND.NEXT_ATTEMPT_AT, dueAt)
                .onConflict(HELD_REFUND.REFERENCE).doNothing()
                .execute() == 1;
    }

    public Optional<HeldRefund> find(String reference) {
        return dsl.selectFrom(HELD_REFUND).where(HELD_REFUND.REFERENCE.eq(reference))
                .fetchOptional(HeldRefundRepository::map);
    }

    /** Row lock for the rest of the transaction: one attempt at a time for one refund, whoever asks. */
    public Optional<HeldRefund> lock(String reference) {
        return dsl.selectFrom(HELD_REFUND).where(HELD_REFUND.REFERENCE.eq(reference)).forUpdate()
                .fetchOptional(HeldRefundRepository::map);
    }

    public List<HeldRefund> forLoan(UUID loanId) {
        return dsl.selectFrom(HELD_REFUND).where(HELD_REFUND.LOAN_ID.eq(loanId)).orderBy(HELD_REFUND.CREATED_AT)
                .fetch(HeldRefundRepository::map);
    }

    /** References of refunds still waiting whose time has come, oldest first. */
    public List<String> dueReferences(Instant now, int limit) {
        return dsl.select(HELD_REFUND.REFERENCE).from(HELD_REFUND)
                .where(HELD_REFUND.STATUS.eq(Status.PENDING.name()).and(HELD_REFUND.NEXT_ATTEMPT_AT.le(now)))
                .orderBy(HELD_REFUND.NEXT_ATTEMPT_AT, HELD_REFUND.REFERENCE)
                .limit(limit)
                .fetch(HELD_REFUND.REFERENCE);
    }

    public void markRefunded(String reference, String refundIds) {
        dsl.update(HELD_REFUND)
                .set(HELD_REFUND.STATUS, Status.REFUNDED.name())
                .set(HELD_REFUND.REFUND_IDS, refundIds)
                .set(HELD_REFUND.LAST_ERROR, (String) null)
                .set(HELD_REFUND.UPDATED_AT, PgSql.now())
                .where(HELD_REFUND.REFERENCE.eq(reference))
                .execute();
    }

    /** A failed attempt: the count goes up and the next one is scheduled, or the refund is given up on. */
    public void recordFailure(String reference, int attempts, String error, Status status, Instant nextAttemptAt) {
        dsl.update(HELD_REFUND)
                .set(HELD_REFUND.STATUS, status.name())
                .set(HELD_REFUND.ATTEMPTS, attempts)
                .set(HELD_REFUND.LAST_ERROR, error)
                .set(HELD_REFUND.NEXT_ATTEMPT_AT, nextAttemptAt)
                .set(HELD_REFUND.UPDATED_AT, PgSql.now())
                .where(HELD_REFUND.REFERENCE.eq(reference))
                .execute();
    }

    private static HeldRefund map(HeldRefundRecord r) {
        return new HeldRefund(r.getReference(), r.getLoanId(), r.getMemberId(), r.getAmountCents(),
                Status.valueOf(r.getStatus()), r.getAttempts(), r.getLastError(), r.getNextAttemptAt(), r.getRefundIds());
    }
}
