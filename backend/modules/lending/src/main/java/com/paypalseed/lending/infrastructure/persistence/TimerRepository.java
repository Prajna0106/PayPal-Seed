package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.LOAN_TIMER;
import static org.jooq.impl.DSL.excluded;

import com.paypalseed.engines.schedule.TimerPlanner.Kind;
import com.paypalseed.engines.schedule.TimerPlanner.Timer;
import com.paypalseed.lending.domain.model.LoanRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Durable per-loan timers (ADR 0007). A fired timer keeps its row, so it is never planned twice. */
@Repository
public class TimerRepository {

    private final DSLContext dsl;

    public TimerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Makes the unfired timers of a loan equal to {@code planned}: new ones are inserted, moved ones
     * re-dated, stale ones deleted. Timers that already fired are left alone.
     */
    public void replacePlanned(UUID loanId, Set<Timer> planned) {
        var stale = LOAN_TIMER.LOAN_ID.eq(loanId).and(LOAN_TIMER.FIRED_ON.isNull());
        if (!planned.isEmpty()) {
            stale = stale.and(DSL.row(LOAN_TIMER.KIND, LOAN_TIMER.REF)
                    .notIn(planned.stream().map(t -> DSL.row(t.kind().name(), t.ref())).toList()));
        }
        dsl.deleteFrom(LOAN_TIMER).where(stale).execute();
        if (planned.isEmpty()) {
            return;
        }
        int shard = LoanRow.shardOf(loanId);
        var rows = dsl.insertInto(LOAN_TIMER,
                LOAN_TIMER.LOAN_ID, LOAN_TIMER.KIND, LOAN_TIMER.REF, LOAN_TIMER.FIRE_ON, LOAN_TIMER.SHARD);
        for (Timer t : planned) {
            rows = rows.values(loanId, t.kind().name(), t.ref(), t.fireOn(), shard);
        }
        rows.onConflict(LOAN_TIMER.LOAN_ID, LOAN_TIMER.KIND, LOAN_TIMER.REF)
                .doUpdate()
                .set(LOAN_TIMER.FIRE_ON, excluded(LOAN_TIMER.FIRE_ON))
                .where(LOAN_TIMER.FIRED_ON.isNull().and(LOAN_TIMER.FIRE_ON.ne(excluded(LOAN_TIMER.FIRE_ON))))
                .execute();
    }

    public List<Timer> due(UUID loanId, LocalDate asOf) {
        return dsl.select(LOAN_TIMER.KIND, LOAN_TIMER.REF, LOAN_TIMER.FIRE_ON)
                .from(LOAN_TIMER)
                .where(LOAN_TIMER.LOAN_ID.eq(loanId).and(LOAN_TIMER.FIRED_ON.isNull()).and(LOAN_TIMER.FIRE_ON.le(asOf)))
                .orderBy(LOAN_TIMER.FIRE_ON, LOAN_TIMER.KIND, LOAN_TIMER.REF)
                .fetch(TimerRepository::timer);
    }

    public void markFired(UUID loanId, Timer timer, LocalDate firedOn) {
        dsl.update(LOAN_TIMER)
                .set(LOAN_TIMER.FIRED_ON, firedOn)
                .where(LOAN_TIMER.LOAN_ID.eq(loanId)
                        .and(LOAN_TIMER.KIND.eq(timer.kind().name()))
                        .and(LOAN_TIMER.REF.eq(timer.ref()))
                        .and(LOAN_TIMER.FIRED_ON.isNull()))
                .execute();
    }

    /**
     * Timers mode work queue: loans in a shard group with at least one unfired timer due by {@code asOf},
     * in loan id order, starting after {@code after} (null for the first page).
     */
    public List<UUID> loansWithDueTimers(LocalDate asOf, int shardGroup, int shardCount, int limit, UUID after) {
        var condition = LOAN_TIMER.FIRED_ON.isNull()
                .and(LOAN_TIMER.FIRE_ON.le(asOf))
                .and(LOAN_TIMER.SHARD.in(LoanRow.shardsOfGroup(shardGroup, shardCount)));
        if (after != null) {
            condition = condition.and(LOAN_TIMER.LOAN_ID.gt(after));
        }
        return dsl.selectDistinct(LOAN_TIMER.LOAN_ID).from(LOAN_TIMER).where(condition)
                .orderBy(LOAN_TIMER.LOAN_ID).limit(limit).fetch(LOAN_TIMER.LOAN_ID);
    }

    public List<Timer> unfired(UUID loanId) {
        return dsl.select(LOAN_TIMER.KIND, LOAN_TIMER.REF, LOAN_TIMER.FIRE_ON)
                .from(LOAN_TIMER)
                .where(LOAN_TIMER.LOAN_ID.eq(loanId).and(LOAN_TIMER.FIRED_ON.isNull()))
                .orderBy(LOAN_TIMER.FIRE_ON, LOAN_TIMER.KIND, LOAN_TIMER.REF)
                .fetch(TimerRepository::timer);
    }

    private static Timer timer(Record3<String, String, LocalDate> r) {
        return new Timer(Kind.valueOf(r.value1()), r.value2(), r.value3());
    }
}
