package com.paypalseed.identity.infrastructure.persistence;

import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.MEMBER;
import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.SESSION;
import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.STAFF;

import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {

    public record StoredSession(String actorType, UUID actorId, Instant lastSeenAt, Instant expiresAt,
                                Instant revokedAt) {
    }

    public record StoredMember(UUID id, boolean active) {
    }

    private final DSLContext dsl;

    public IdentityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insertSession(String tokenHash, String actorType, UUID actorId, Instant authTime, Instant expiresAt) {
        dsl.insertInto(SESSION)
                .set(SESSION.TOKEN_HASH, tokenHash)
                .set(SESSION.ACTOR_TYPE, actorType)
                .set(SESSION.ACTOR_ID, actorId)
                .set(SESSION.AUTH_TIME, authTime)
                .set(SESSION.LAST_SEEN_AT, authTime)
                .set(SESSION.EXPIRES_AT, expiresAt)
                .execute();
    }

    public Optional<StoredSession> findSession(String tokenHash) {
        return dsl.selectFrom(SESSION).where(SESSION.TOKEN_HASH.eq(tokenHash))
                .fetchOptional(r -> new StoredSession(r.getActorType(), r.getActorId(), r.getLastSeenAt(),
                        r.getExpiresAt(), r.getRevokedAt()));
    }

    public void touchSession(String tokenHash, Instant seenAt) {
        dsl.update(SESSION)
                .set(SESSION.LAST_SEEN_AT, seenAt)
                .where(SESSION.TOKEN_HASH.eq(tokenHash))
                .execute();
    }

    public void revokeSession(String tokenHash) {
        dsl.update(SESSION)
                .set(SESSION.REVOKED_AT, PgSql.now())
                .where(SESSION.TOKEN_HASH.eq(tokenHash).and(SESSION.REVOKED_AT.isNull()))
                .execute();
    }

    /** Creates the member with {@code newId}, or refreshes the one PayPal already knows under this subject. */
    public StoredMember upsertMember(UUID newId, String subject, String payerId, String email, String displayName,
                                     boolean verifiedAccount) {
        return dsl.insertInto(MEMBER)
                .set(MEMBER.ID, newId)
                .set(MEMBER.PAYPAL_SUBJECT, subject)
                .set(MEMBER.PAYER_ID, payerId)
                .set(MEMBER.EMAIL, email)
                .set(MEMBER.DISPLAY_NAME, displayName)
                .set(MEMBER.VERIFIED_ACCOUNT, verifiedAccount)
                .onConflict(MEMBER.PAYPAL_SUBJECT)
                .doUpdate()
                .set(MEMBER.PAYER_ID, DSL.excluded(MEMBER.PAYER_ID))
                .set(MEMBER.EMAIL, DSL.excluded(MEMBER.EMAIL))
                .set(MEMBER.DISPLAY_NAME, DSL.excluded(MEMBER.DISPLAY_NAME))
                .set(MEMBER.VERIFIED_ACCOUNT, DSL.excluded(MEMBER.VERIFIED_ACCOUNT))
                .returning(MEMBER.ID, MEMBER.STATUS)
                .fetchOne(r -> new StoredMember(r.getId(), "ACTIVE".equals(r.getStatus())));
    }

    public Optional<String> memberDisplayName(UUID memberId) {
        return dsl.select(MEMBER.DISPLAY_NAME).from(MEMBER).where(MEMBER.ID.eq(memberId))
                .fetchOptional(r -> r.value1());
    }

    public boolean memberIsActive(UUID memberId) {
        return dsl.fetchExists(dsl.selectOne().from(MEMBER)
                .where(MEMBER.ID.eq(memberId).and(MEMBER.STATUS.eq("ACTIVE"))));
    }

    /** The roles of an active staff user; empty if they don't exist or are suspended. */
    public Optional<Set<String>> activeStaffRoles(UUID staffId) {
        return dsl.select(STAFF.ROLES).from(STAFF)
                .where(STAFF.ID.eq(staffId).and(STAFF.STATUS.eq("ACTIVE")))
                .fetchOptional(r -> Set.of(r.value1()));
    }
}
