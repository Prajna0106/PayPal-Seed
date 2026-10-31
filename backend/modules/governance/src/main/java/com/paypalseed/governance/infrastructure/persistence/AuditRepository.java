package com.paypalseed.governance.infrastructure.persistence;

import static com.paypalseed.governance.infrastructure.persistence.jooq.Tables.AUDIT_ENTRY;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** {@code governance.audit_entry}: the append-only, hash-chained audit log. */
@Repository
public class AuditRepository {

    public record AuditRow(long seq, String entryType, UUID subjectId, String actorType, String actorId, String payloadJson,
                           byte[] prevHash, byte[] hash, Instant createdAt) {
    }

    private final DSLContext dsl;

    public AuditRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Serialises chain writers until the transaction ends. Must be taken before reading the last hash; the
     * insert trigger takes the same lock and re-checks the link.
     */
    public void lockChain() {
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext('governance.audit_entry'))");
    }

    public Optional<byte[]> lastHash() {
        return dsl.select(AUDIT_ENTRY.HASH).from(AUDIT_ENTRY).orderBy(AUDIT_ENTRY.SEQ.desc()).limit(1)
                .fetchOptional(AUDIT_ENTRY.HASH);
    }

    public long insert(String entryType, UUID subjectId, String actorType, String actorId, String payloadJson,
                       byte[] prevHash, byte[] hash, Instant createdAt) {
        return dsl.insertInto(AUDIT_ENTRY)
                .set(AUDIT_ENTRY.ENTRY_TYPE, entryType)
                .set(AUDIT_ENTRY.SUBJECT_ID, subjectId)
                .set(AUDIT_ENTRY.ACTOR_TYPE, actorType)
                .set(AUDIT_ENTRY.ACTOR_ID, actorId)
                .set(AUDIT_ENTRY.PAYLOAD, JSONB.valueOf(payloadJson))
                .set(AUDIT_ENTRY.PREV_HASH, prevHash)
                .set(AUDIT_ENTRY.HASH, hash)
                .set(AUDIT_ENTRY.CREATED_AT, createdAt)
                .returning(AUDIT_ENTRY.SEQ)
                .fetchSingle()
                .getSeq();
    }

    /** The sequence number of the entry that recorded a decision (replayed decisions return it again). */
    public Optional<Long> seqOfDecision(UUID subjectId, UUID decisionId) {
        return dsl.select(AUDIT_ENTRY.SEQ).from(AUDIT_ENTRY)
                .where(AUDIT_ENTRY.SUBJECT_ID.eq(subjectId))
                .and(AUDIT_ENTRY.ENTRY_TYPE.eq("APPROVAL_DECISION"))
                .and(DSL.field("{0} ->> 'decisionId'", String.class, AUDIT_ENTRY.PAYLOAD).eq(decisionId.toString()))
                .fetchOptional(AUDIT_ENTRY.SEQ);
    }

    /** Keyset page in chain order, starting after {@code afterSeq}. */
    public List<AuditRow> page(long afterSeq, int limit) {
        return dsl.selectFrom(AUDIT_ENTRY).where(AUDIT_ENTRY.SEQ.gt(afterSeq)).orderBy(AUDIT_ENTRY.SEQ).limit(limit)
                .fetch(r -> new AuditRow(r.getSeq(), r.getEntryType(), r.getSubjectId(), r.getActorType(), r.getActorId(),
                        r.getPayload().data(), r.getPrevHash(), r.getHash(), r.getCreatedAt()));
    }
}
