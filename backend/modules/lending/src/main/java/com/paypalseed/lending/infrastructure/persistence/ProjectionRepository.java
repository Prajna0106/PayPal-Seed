package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.BUCKET_TRANSITION;
import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.INSTALMENT;
import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.INSTALMENT_ALLOCATION;
import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.INSTALMENT_MISSED;
import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.TRANSACTION_ALLOCATION;

import com.paypalseed.engines.schedule.Allocation;
import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.Allocation.Portion;
import com.paypalseed.engines.schedule.Bucket;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Derived state rebuilt by every recalculation, and the append-only delinquency facts. */
@Repository
public class ProjectionRepository {

    public record Transition(LocalDate asOf, String fromBucket, String toBucket, int dpd, String cause) {
    }

    private record PortionKey(UUID transactionId, int seq) {
    }

    private final DSLContext dsl;

    public ProjectionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Replaces the loan's projection with one multi-row insert per table, however many instalments it has. */
    public void replaceAllocation(UUID loanId, int version, Allocation allocation, List<UUID> transactionIds) {
        dsl.deleteFrom(INSTALMENT_ALLOCATION).where(INSTALMENT_ALLOCATION.LOAN_ID.eq(loanId)).execute();
        if (!allocation.instalments().isEmpty()) {
            var rows = dsl.insertInto(INSTALMENT_ALLOCATION,
                    INSTALMENT_ALLOCATION.LOAN_ID, INSTALMENT_ALLOCATION.VERSION, INSTALMENT_ALLOCATION.SEQ,
                    INSTALMENT_ALLOCATION.PAID_CENTS, INSTALMENT_ALLOCATION.PAID_IN_ADVANCE_CENTS,
                    INSTALMENT_ALLOCATION.FULLY_PAID_ON);
            for (InstalmentAllocation a : allocation.instalments()) {
                rows = rows.values(loanId, version, a.seq(), a.paidCents(), a.paidInAdvanceCents(), a.fullyPaidOn());
            }
            rows.execute();
        }
        if (!transactionIds.isEmpty()) {
            dsl.deleteFrom(TRANSACTION_ALLOCATION).where(TRANSACTION_ALLOCATION.TRANSACTION_ID.in(transactionIds)).execute();
        }
        // One event can reach the same instalment through two waterfall steps; the row holds their sum.
        Map<PortionKey, Long> centsByKey = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<Portion>> e : allocation.portionsByEvent().entrySet()) {
            for (Portion p : e.getValue()) {
                centsByKey.merge(new PortionKey(e.getKey(), p.seq()), p.cents(), Long::sum);
            }
        }
        centsByKey.values().removeIf(cents -> cents == 0);
        if (!centsByKey.isEmpty()) {
            var rows = dsl.insertInto(TRANSACTION_ALLOCATION,
                    TRANSACTION_ALLOCATION.TRANSACTION_ID, TRANSACTION_ALLOCATION.VERSION, TRANSACTION_ALLOCATION.SEQ,
                    TRANSACTION_ALLOCATION.CENTS);
            for (Map.Entry<PortionKey, Long> e : centsByKey.entrySet()) {
                rows = rows.values(e.getKey().transactionId(), version, e.getKey().seq(), e.getValue());
            }
            rows.execute();
        }
    }

    public Map<Integer, InstalmentAllocation> allocation(UUID loanId) {
        return dsl
                .select(INSTALMENT_ALLOCATION.SEQ, INSTALMENT.AMOUNT_CENTS, INSTALMENT_ALLOCATION.PAID_CENTS,
                        INSTALMENT_ALLOCATION.PAID_IN_ADVANCE_CENTS, INSTALMENT_ALLOCATION.FULLY_PAID_ON)
                .from(INSTALMENT_ALLOCATION)
                .join(INSTALMENT).on(INSTALMENT.LOAN_ID.eq(INSTALMENT_ALLOCATION.LOAN_ID)
                        .and(INSTALMENT.VERSION.eq(INSTALMENT_ALLOCATION.VERSION))
                        .and(INSTALMENT.SEQ.eq(INSTALMENT_ALLOCATION.SEQ)))
                .where(INSTALMENT_ALLOCATION.LOAN_ID.eq(loanId))
                .fetch(r -> new InstalmentAllocation(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()))
                .stream()
                .collect(Collectors.toMap(InstalmentAllocation::seq, Function.identity()));
    }

    /** Records the fact once; returns true when it is new. */
    public boolean recordMissed(UUID loanId, int version, int seq, LocalDate dueDate, LocalDate missedOn) {
        return dsl.insertInto(INSTALMENT_MISSED)
                .set(INSTALMENT_MISSED.LOAN_ID, loanId)
                .set(INSTALMENT_MISSED.VERSION, version)
                .set(INSTALMENT_MISSED.SEQ, seq)
                .set(INSTALMENT_MISSED.DUE_DATE, dueDate)
                .set(INSTALMENT_MISSED.MISSED_ON, missedOn)
                .onConflictDoNothing()
                .execute() == 1;
    }

    public int missedCount(UUID loanId) {
        return dsl.fetchCount(INSTALMENT_MISSED, INSTALMENT_MISSED.LOAN_ID.eq(loanId));
    }

    public void recordTransition(UUID loanId, LocalDate asOf, Bucket from, Bucket to, int dpd, String cause) {
        dsl.insertInto(BUCKET_TRANSITION)
                .set(BUCKET_TRANSITION.LOAN_ID, loanId)
                .set(BUCKET_TRANSITION.AS_OF, asOf)
                .set(BUCKET_TRANSITION.FROM_BUCKET, from.name())
                .set(BUCKET_TRANSITION.TO_BUCKET, to.name())
                .set(BUCKET_TRANSITION.DPD, dpd)
                .set(BUCKET_TRANSITION.CAUSE, cause)
                .execute();
    }

    public List<Transition> transitions(UUID loanId) {
        return dsl
                .select(BUCKET_TRANSITION.AS_OF, BUCKET_TRANSITION.FROM_BUCKET, BUCKET_TRANSITION.TO_BUCKET,
                        BUCKET_TRANSITION.DPD, BUCKET_TRANSITION.CAUSE)
                .from(BUCKET_TRANSITION)
                .where(BUCKET_TRANSITION.LOAN_ID.eq(loanId))
                .orderBy(BUCKET_TRANSITION.ID)
                .fetch(r -> new Transition(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
    }
}
