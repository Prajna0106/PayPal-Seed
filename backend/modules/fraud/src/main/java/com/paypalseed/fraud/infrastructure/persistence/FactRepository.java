package com.paypalseed.fraud.infrastructure.persistence;

import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.FACT;
import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.HASH_SALT;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Fact;
import com.paypalseed.fraud.infrastructure.persistence.jooq.tables.records.FactRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** What happened: append-only facts, the named locks that serialise evaluation, and the hashing salt. */
@Repository
public class FactRepository {

    private final DSLContext dsl;
    private final FraudJson json;

    FactRepository(DSLContext dsl, FraudJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    /** The new fact's id, or empty when this source key was already recorded. */
    public Optional<Long> insert(UUID memberId, FactKind kind, Instant occurredAt, String sourceKey, Long amountCents,
                                 String refHash, Map<String, String> attributes) {
        return dsl.insertInto(FACT)
                .set(FACT.MEMBER_ID, memberId)
                .set(FACT.KIND, kind.name())
                .set(FACT.OCCURRED_AT, occurredAt)
                .set(FACT.SOURCE_KEY, sourceKey)
                .set(FACT.AMOUNT_CENTS, amountCents)
                .set(FACT.REF_HASH, refHash)
                .set(FACT.ATTRIBUTES, json.write(attributes))
                .onConflict(FACT.SOURCE_KEY).doNothing()
                .returning(FACT.ID)
                .fetchOptional(FACT.ID);
    }

    public List<Fact> factsOf(UUID memberId, Instant since) {
        return dsl.selectFrom(FACT)
                .where(FACT.MEMBER_ID.eq(memberId).and(FACT.OCCURRED_AT.ge(since)))
                .orderBy(FACT.OCCURRED_AT, FACT.ID)
                .fetch(this::fact);
    }

    public Map<Long, Fact> byIds(Collection<Long> ids) {
        Map<Long, Fact> out = new HashMap<>();
        if (!ids.isEmpty()) {
            dsl.selectFrom(FACT).where(FACT.ID.in(ids)).forEach(r -> out.put(r.getId(), fact(r)));
        }
        return out;
    }

    /** For each reference, how many members other than {@code memberId} have a payer fact with it. */
    public Map<String, Integer> otherMembersByRef(Set<String> refHashes, UUID memberId) {
        Map<String, Integer> out = new HashMap<>();
        if (!refHashes.isEmpty()) {
            dsl.select(FACT.REF_HASH, DSL.countDistinct(FACT.MEMBER_ID))
                    .from(FACT)
                    .where(FACT.REF_HASH.in(refHashes).and(FACT.KIND.eq(FactKind.PAYER_SEEN.name())).and(FACT.MEMBER_ID.ne(memberId)))
                    .groupBy(FACT.REF_HASH)
                    .forEach(r -> out.put(r.value1(), r.value2()));
        }
        return out;
    }

    /** Members, other than {@code memberId}, that share any of the references; the cap keeps a hot payer cheap. */
    public List<UUID> membersSharing(Set<String> refHashes, UUID memberId, int limit) {
        if (refHashes.isEmpty()) {
            return List.of();
        }
        return dsl.selectDistinct(FACT.MEMBER_ID).from(FACT)
                .where(FACT.REF_HASH.in(refHashes).and(FACT.KIND.eq(FactKind.PAYER_SEEN.name())).and(FACT.MEMBER_ID.ne(memberId)))
                .limit(limit)
                .fetch(FACT.MEMBER_ID);
    }

    /** Takes a named lock until the transaction ends. Callers always lock in the same order so two never deadlock. */
    public void lock(String key) {
        dsl.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", key);
    }

    public String salt() {
        return dsl.select(HASH_SALT.SALT).from(HASH_SALT).where(HASH_SALT.ID.eq(1)).fetchOne(HASH_SALT.SALT);
    }

    private Fact fact(FactRecord r) {
        return new Fact(r.getId(), r.getMemberId(), FactKind.valueOf(r.getKind()), r.getOccurredAt(), r.getAmountCents(),
                r.getRefHash(), json.strings(r.getAttributes()));
    }
}
