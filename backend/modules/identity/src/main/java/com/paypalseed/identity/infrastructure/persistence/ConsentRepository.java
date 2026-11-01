package com.paypalseed.identity.infrastructure.persistence;

import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.CONSENT;

import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.domain.model.ConsentEntry;
import com.paypalseed.identity.infrastructure.persistence.jooq.tables.records.ConsentRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** The ledger accepts two writes: append a line, and close an open line. Anything else is rejected by a trigger. */
@Repository
public class ConsentRepository {

    private final DSLContext dsl;

    ConsentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void append(ConsentEntry e) {
        dsl.insertInto(CONSENT)
                .set(CONSENT.ID, e.id())
                .set(CONSENT.MEMBER_ID, e.memberId())
                .set(CONSENT.TYPE, e.type().name())
                .set(CONSENT.VERSION, e.version())
                .set(CONSENT.GRANTED_AT, e.grantedAt())
                .set(CONSENT.REVOKED_AT, e.revokedAt())
                .execute();
    }

    /** Sets the revocation time of an open line; true when a line was closed. */
    public boolean close(UUID id, Instant revokedAt) {
        return dsl.update(CONSENT).set(CONSENT.REVOKED_AT, revokedAt)
                .where(CONSENT.ID.eq(id).and(CONSENT.REVOKED_AT.isNull()))
                .execute() == 1;
    }

    /** Newest first. */
    public List<ConsentEntry> forMember(UUID memberId) {
        return dsl.selectFrom(CONSENT).where(CONSENT.MEMBER_ID.eq(memberId))
                .orderBy(CONSENT.GRANTED_AT.desc(), CONSENT.ID.desc())
                .fetch(ConsentRepository::map);
    }

    public boolean hasActive(UUID memberId, ConsentType type) {
        return dsl.fetchExists(CONSENT, CONSENT.MEMBER_ID.eq(memberId).and(CONSENT.TYPE.eq(type.name()))
                .and(CONSENT.REVOKED_AT.isNull()));
    }

    private static ConsentEntry map(ConsentRecord r) {
        return new ConsentEntry(r.getId(), r.getMemberId(), ConsentType.valueOf(r.getType()), r.getVersion(),
                r.getGrantedAt(), r.getRevokedAt());
    }
}
