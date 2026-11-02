package com.paypalseed.fraud.infrastructure.persistence;

import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.SIGNAL;

import com.paypalseed.fraud.domain.model.Signal;
import com.paypalseed.fraud.infrastructure.persistence.jooq.tables.records.SignalRecord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Rules that fired. A signal is written once and can be attached to one flag once. */
@Repository
public class SignalRepository {

    private static final String FACT_IDS = "factIds";

    public record StoredSignal(long id, UUID flagId, Signal signal) {
    }

    private final DSLContext dsl;
    private final FraudJson json;

    SignalRepository(DSLContext dsl, FraudJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    /** True when the finding is new; the same rule on the same facts and numbers is stored once. */
    public boolean insertIfAbsent(UUID memberId, Signal s) {
        Map<String, Object> evidence = new LinkedHashMap<>(s.evidence());
        evidence.put(FACT_IDS, s.factIds());
        return dsl.insertInto(SIGNAL)
                .set(SIGNAL.MEMBER_ID, memberId)
                .set(SIGNAL.CODE, s.code())
                .set(SIGNAL.WEIGHT, s.weight())
                .set(SIGNAL.FINGERPRINT, s.fingerprint())
                .set(SIGNAL.EXPLANATION, s.explanation())
                .set(SIGNAL.EVIDENCE, json.write(evidence))
                .set(SIGNAL.DETECTED_AT, s.detectedAt())
                .onConflict(SIGNAL.MEMBER_ID, SIGNAL.CODE, SIGNAL.FINGERPRINT).doNothing()
                .execute() == 1;
    }

    /** Signals that have not yet reached a flag: the member's score so far. */
    public List<StoredSignal> unattached(UUID memberId) {
        return dsl.selectFrom(SIGNAL).where(SIGNAL.MEMBER_ID.eq(memberId).and(SIGNAL.FLAG_ID.isNull())).orderBy(SIGNAL.ID)
                .fetch(this::stored);
    }

    public List<StoredSignal> ofFlag(UUID flagId) {
        return dsl.selectFrom(SIGNAL).where(SIGNAL.FLAG_ID.eq(flagId)).orderBy(SIGNAL.ID).fetch(this::stored);
    }

    public Map<UUID, List<String>> codesByFlag(Collection<UUID> flagIds) {
        Map<UUID, List<String>> out = new LinkedHashMap<>();
        if (!flagIds.isEmpty()) {
            dsl.selectDistinct(SIGNAL.FLAG_ID, SIGNAL.CODE).from(SIGNAL).where(SIGNAL.FLAG_ID.in(flagIds))
                    .orderBy(SIGNAL.FLAG_ID, SIGNAL.CODE)
                    .forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(r.value2()));
        }
        return out;
    }

    public void attach(UUID flagId, Collection<Long> signalIds) {
        if (!signalIds.isEmpty()) {
            dsl.update(SIGNAL).set(SIGNAL.FLAG_ID, flagId).where(SIGNAL.ID.in(signalIds).and(SIGNAL.FLAG_ID.isNull())).execute();
        }
    }

    private StoredSignal stored(SignalRecord r) {
        Map<String, Object> evidence = new LinkedHashMap<>(json.objects(r.getEvidence()));
        List<Long> factIds = new ArrayList<>();
        if (evidence.remove(FACT_IDS) instanceof List<?> ids) {
            ids.forEach(id -> factIds.add(((Number) id).longValue()));
        }
        return new StoredSignal(r.getId(), r.getFlagId(),
                new Signal(r.getCode(), r.getWeight(), r.getExplanation(), evidence, factIds, r.getDetectedAt()));
    }
}
