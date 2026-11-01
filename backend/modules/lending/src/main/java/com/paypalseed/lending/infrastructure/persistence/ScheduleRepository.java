package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.INSTALMENT;
import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.SCHEDULE_VERSION;

import com.paypalseed.engines.schedule.Instalment;
import com.paypalseed.engines.schedule.Schedule;
import com.paypalseed.engines.schedule.Schedule.ScheduleReason;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduleRepository {

    public record StoredVersion(Schedule schedule, String causedBy, LocalDate createdOn) {
    }

    private final DSLContext dsl;

    public ScheduleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Header and instalments go in as two statements; the deferred INV-1 trigger checks them at commit. */
    public void insert(UUID loanId, Schedule schedule, String policyVersion, String causedBy, LocalDate createdOn) {
        dsl.insertInto(SCHEDULE_VERSION)
                .set(SCHEDULE_VERSION.LOAN_ID, loanId)
                .set(SCHEDULE_VERSION.VERSION, schedule.version())
                .set(SCHEDULE_VERSION.REASON, schedule.reason().name())
                .set(SCHEDULE_VERSION.PRINCIPAL_CENTS, schedule.principalCents())
                .set(SCHEDULE_VERSION.INSTALMENT_COUNT, schedule.size())
                .set(SCHEDULE_VERSION.POLICY_VERSION, policyVersion)
                .set(SCHEDULE_VERSION.CAUSED_BY, causedBy)
                .set(SCHEDULE_VERSION.CREATED_ON, createdOn)
                .execute();
        var rows = dsl.insertInto(INSTALMENT,
                INSTALMENT.LOAN_ID, INSTALMENT.VERSION, INSTALMENT.SEQ, INSTALMENT.DUE_DATE, INSTALMENT.AMOUNT_CENTS);
        for (Instalment inst : schedule.instalments()) {
            rows = rows.values(loanId, schedule.version(), inst.seq(), inst.dueDate(), inst.amountCents());
        }
        rows.execute();
    }

    /** The version an approval created, if it was already applied. */
    public Optional<Integer> versionCausedBy(UUID loanId, String causedBy) {
        return dsl.select(SCHEDULE_VERSION.VERSION).from(SCHEDULE_VERSION)
                .where(SCHEDULE_VERSION.LOAN_ID.eq(loanId).and(SCHEDULE_VERSION.CAUSED_BY.eq(causedBy)))
                .fetchOptional(SCHEDULE_VERSION.VERSION);
    }

    public Optional<Schedule> load(UUID loanId, int version, int dueDay) {
        return versions(loanId, dueDay).stream()
                .map(StoredVersion::schedule)
                .filter(s -> s.version() == version)
                .findFirst();
    }

    public Schedule loadCurrent(UUID loanId, int currentVersion, int dueDay) {
        return load(loanId, currentVersion, dueDay)
                .orElseThrow(() -> new IllegalStateException("loan " + loanId + " has no schedule version " + currentVersion));
    }

    public List<StoredVersion> versions(UUID loanId, int dueDay) {
        record Header(int version, String reason, long principal, String causedBy, LocalDate createdOn) {
        }
        List<Header> headers = dsl
                .select(SCHEDULE_VERSION.VERSION, SCHEDULE_VERSION.REASON, SCHEDULE_VERSION.PRINCIPAL_CENTS,
                        SCHEDULE_VERSION.CAUSED_BY, SCHEDULE_VERSION.CREATED_ON)
                .from(SCHEDULE_VERSION)
                .where(SCHEDULE_VERSION.LOAN_ID.eq(loanId))
                .orderBy(SCHEDULE_VERSION.VERSION)
                .fetch(r -> new Header(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
        Map<Integer, List<Instalment>> byVersion = dsl
                .select(INSTALMENT.VERSION, INSTALMENT.SEQ, INSTALMENT.DUE_DATE, INSTALMENT.AMOUNT_CENTS)
                .from(INSTALMENT)
                .where(INSTALMENT.LOAN_ID.eq(loanId))
                .orderBy(INSTALMENT.VERSION, INSTALMENT.SEQ)
                .fetch(r -> Map.entry(r.value1(), new Instalment(r.value2(), r.value3(), r.value4())))
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toCollection(ArrayList::new))));
        return headers.stream()
                .map(h -> new StoredVersion(new Schedule(h.version(), ScheduleReason.valueOf(h.reason()), h.principal(), dueDay,
                        byVersion.getOrDefault(h.version(), List.of())), h.causedBy(), h.createdOn()))
                .toList();
    }
}
