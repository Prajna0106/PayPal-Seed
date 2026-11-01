package com.paypalseed.hardship.infrastructure.persistence;

import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.SCORECARD_RUN;
import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.SIGNAL;

import com.paypalseed.engines.rules.hardship.Signal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Scorecard runs and their signals: append-only, one run per loan and scan date. */
@Repository
public class ScorecardRepository {

    public record Run(UUID id, UUID loanId, UUID memberId, LocalDate scanDate, int scheduleVersion, int targetSeq,
                      String rawLevel, String level, int belowCount, int points, Map<String, Long> features,
                      String policyVersion, String scorecardVersion) {
    }

    public record StoredSignal(String code, int points, Map<String, Long> evidence, String source, UUID agentRunId) {
    }

    private final DSLContext dsl;
    private final JsonColumns json;

    ScorecardRepository(DSLContext dsl, JsonColumns json) {
        this.dsl = dsl;
        this.json = json;
    }

    /** Returns false when the loan was already scored on that date (the stored run stands). */
    public boolean insert(Run run) {
        return dsl.insertInto(SCORECARD_RUN)
                .set(SCORECARD_RUN.ID, run.id())
                .set(SCORECARD_RUN.LOAN_ID, run.loanId())
                .set(SCORECARD_RUN.MEMBER_ID, run.memberId())
                .set(SCORECARD_RUN.SCAN_DATE, run.scanDate())
                .set(SCORECARD_RUN.SCHEDULE_VERSION, run.scheduleVersion())
                .set(SCORECARD_RUN.TARGET_SEQ, run.targetSeq())
                .set(SCORECARD_RUN.RAW_LEVEL, run.rawLevel())
                .set(SCORECARD_RUN.LEVEL, run.level())
                .set(SCORECARD_RUN.BELOW_COUNT, run.belowCount())
                .set(SCORECARD_RUN.POINTS, run.points())
                .set(SCORECARD_RUN.FEATURES, json.write(run.features()))
                .set(SCORECARD_RUN.POLICY_VERSION, run.policyVersion())
                .set(SCORECARD_RUN.SCORECARD_VERSION, run.scorecardVersion())
                .onConflict(SCORECARD_RUN.LOAN_ID, SCORECARD_RUN.SCAN_DATE)
                .doNothing()
                .execute() == 1;
    }

    public Optional<Run> find(UUID loanId, LocalDate scanDate) {
        return dsl.selectFrom(SCORECARD_RUN)
                .where(SCORECARD_RUN.LOAN_ID.eq(loanId).and(SCORECARD_RUN.SCAN_DATE.eq(scanDate)))
                .fetchOptional(this::map);
    }

    public Optional<Run> find(UUID runId) {
        return dsl.selectFrom(SCORECARD_RUN).where(SCORECARD_RUN.ID.eq(runId)).fetchOptional(this::map);
    }

    /** The latest earlier run of the same instalment cycle: hysteresis state carries only within a cycle. */
    public Optional<Run> previousInCycle(UUID loanId, int targetSeq, LocalDate before) {
        return dsl.selectFrom(SCORECARD_RUN)
                .where(SCORECARD_RUN.LOAN_ID.eq(loanId))
                .and(SCORECARD_RUN.TARGET_SEQ.eq(targetSeq))
                .and(SCORECARD_RUN.SCAN_DATE.lt(before))
                .orderBy(SCORECARD_RUN.SCAN_DATE.desc())
                .limit(1)
                .fetchOptional(this::map);
    }

    public void insertSignals(UUID runId, List<Signal> signals, String source, UUID agentRunId) {
        for (Signal s : signals) {
            dsl.insertInto(SIGNAL)
                    .set(SIGNAL.RUN_ID, runId)
                    .set(SIGNAL.CODE, s.code())
                    .set(SIGNAL.POINTS, s.points())
                    .set(SIGNAL.EVIDENCE, json.write(s.evidence()))
                    .set(SIGNAL.SOURCE, source)
                    .set(SIGNAL.AGENT_RUN_ID, agentRunId)
                    .onConflict(SIGNAL.RUN_ID, SIGNAL.CODE)
                    .doNothing()
                    .execute();
        }
    }

    public List<StoredSignal> signals(UUID runId) {
        return dsl.selectFrom(SIGNAL)
                .where(SIGNAL.RUN_ID.eq(runId))
                .orderBy(SIGNAL.ID)
                .fetch(r -> new StoredSignal(r.getCode(), r.getPoints(), json.longs(r.getEvidence()), r.getSource(),
                        r.getAgentRunId()));
    }

    private Run map(com.paypalseed.hardship.infrastructure.persistence.jooq.tables.records.ScorecardRunRecord r) {
        return new Run(r.getId(), r.getLoanId(), r.getMemberId(), r.getScanDate(), r.getScheduleVersion(), r.getTargetSeq(),
                r.getRawLevel(), r.getLevel(), r.getBelowCount(), r.getPoints(), json.longs(r.getFeatures()),
                r.getPolicyVersion(), r.getScorecardVersion());
    }
}
