package com.paypalseed.governance.application.usecases;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paypalseed.governance.api.dto.GovernanceDtos.ChainVerification;
import com.paypalseed.governance.domain.model.AuditHash;
import com.paypalseed.governance.infrastructure.persistence.AuditRepository;
import com.paypalseed.governance.infrastructure.persistence.AuditRepository.AuditRow;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hash-chained audit log. Entries are stamped with wall-clock time (not the business clock): the
 * demo clock moves business dates, but an audit trail records when something really happened.
 */
@Service
public class AuditTrail {

    private static final int PAGE = 1_000;

    private final AuditRepository entries;
    private final Clock wallClock = Clock.systemUTC();

    public AuditTrail(AuditRepository entries) {
        this.entries = entries;
    }

    /** Appends one entry and returns its sequence number. The chain lock is held until the transaction ends. */
    @Transactional
    public long append(String entryType, UUID subjectId, String actorType, String actorId, String payloadJson) {
        String payload = CanonicalJson.of(payloadJson);
        entries.lockChain();
        byte[] prev = entries.lastHash().orElseGet(AuditHash::genesis);
        Instant at = Instant.now(wallClock).truncatedTo(ChronoUnit.MICROS);
        byte[] hash = AuditHash.next(prev, canonicalEntry(entryType, subjectId, actorType, actorId, payload, at));
        return entries.insert(entryType, subjectId, actorType, actorId, payload, prev, hash, at);
    }

    /** Recomputes every link from the stored rows. Any edit to any column of any entry breaks the chain from there. */
    @Transactional(readOnly = true)
    public ChainVerification verify() {
        byte[] prev = AuditHash.genesis();
        long after = 0;
        long count = 0;
        List<AuditRow> page;
        while (!(page = entries.page(after, PAGE)).isEmpty()) {
            for (AuditRow row : page) {
                byte[] expected = AuditHash.next(prev, canonicalEntry(row.entryType(), row.subjectId(), row.actorType(),
                        row.actorId(), CanonicalJson.of(row.payloadJson()), row.createdAt()));
                if (!Arrays.equals(row.prevHash(), prev) || !Arrays.equals(row.hash(), expected)) {
                    return new ChainVerification(false, count, row.seq());
                }
                prev = row.hash();
                after = row.seq();
                count++;
            }
        }
        return new ChainVerification(true, count, null);
    }

    static String canonicalEntry(String entryType, UUID subjectId, String actorType, String actorId, String canonicalPayload,
                                 Instant createdAt) {
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        entry.put("entryType", entryType);
        entry.put("subjectId", subjectId == null ? null : subjectId.toString());
        entry.put("actorType", actorType);
        entry.put("actorId", actorId);
        entry.set("payload", CanonicalJson.parse(canonicalPayload));
        entry.put("createdAt", createdAt.toString());
        return CanonicalJson.of(entry);
    }
}
