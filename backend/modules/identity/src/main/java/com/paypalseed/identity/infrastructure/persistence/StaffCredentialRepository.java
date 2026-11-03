package com.paypalseed.identity.infrastructure.persistence;

import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.STAFF;

import com.paypalseed.identity.infrastructure.persistence.jooq.tables.records.StaffRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Staff passwords. The directory itself (names, roles, active) stays in {@link StaffRepository}. */
@Repository
public class StaffCredentialRepository {

    public record StaffAccount(UUID id, String email, String displayName, Set<String> roles, boolean active,
                               String passwordHash, Instant lockedUntil) {
        /** Never prints the hash or personal data. */
        @Override
        public String toString() {
            return "StaffAccount[id=" + id + ", roles=" + roles + ", active=" + active + ", personal data redacted]";
        }
    }

    private final DSLContext dsl;

    StaffCredentialRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Creates the account with {@code newId}, or updates the one with this email. A null password leaves the old one. */
    public UUID saveAccount(UUID newId, String email, String displayName, String[] roles, UUID linkedMemberId,
                            String passwordHash, Instant now) {
        Optional<UUID> existing = dsl.select(STAFF.ID).from(STAFF).where(DSL.lower(STAFF.EMAIL).eq(email))
                .orderBy(STAFF.PASSWORD_HASH.isNull(), STAFF.ID).limit(1).fetchOptional(STAFF.ID);
        if (existing.isEmpty()) {
            insert(newId, email, displayName, roles, linkedMemberId, passwordHash, now);
            return newId;
        }
        var update = dsl.update(STAFF)
                .set(STAFF.EMAIL, email)
                .set(STAFF.DISPLAY_NAME, displayName)
                .set(STAFF.ROLES, roles)
                .set(STAFF.LINKED_MEMBER_ID, linkedMemberId)
                .set(STAFF.UPDATED_AT, now);
        if (passwordHash != null) {
            update = update.set(STAFF.PASSWORD_HASH, passwordHash)
                    .set(STAFF.FAILED_LOGINS, 0)
                    .setNull(STAFF.LOCKED_UNTIL);
        }
        update.where(STAFF.ID.eq(existing.get())).execute();
        return existing.get();
    }

    /** False when someone already has this email: creating a colleague must never take over their account. */
    public boolean insertNew(UUID id, String email, String displayName, String[] roles, String passwordHash, Instant now) {
        if (dsl.fetchExists(dsl.selectOne().from(STAFF).where(DSL.lower(STAFF.EMAIL).eq(email)))) {
            return false;
        }
        return insert(id, email, displayName, roles, null, passwordHash, now);
    }

    private boolean insert(UUID id, String email, String displayName, String[] roles, UUID linkedMemberId, String passwordHash,
                           Instant now) {
        return dsl.insertInto(STAFF)
                .set(STAFF.ID, id)
                .set(STAFF.EMAIL, email)
                .set(STAFF.DISPLAY_NAME, displayName)
                .set(STAFF.ROLES, roles)
                .set(STAFF.LINKED_MEMBER_ID, linkedMemberId)
                .set(STAFF.PASSWORD_HASH, passwordHash)
                .set(STAFF.CREATED_AT, now)
                .set(STAFF.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute() == 1;
    }

    public Optional<StaffAccount> findByEmail(String email) {
        return dsl.selectFrom(STAFF).where(DSL.lower(STAFF.EMAIL).eq(email).and(STAFF.PASSWORD_HASH.isNotNull()))
                .fetchOptional(StaffCredentialRepository::account);
    }

    /** An account someone can log in to and that isn't suspended. Demo personas have no password, so they aren't one. */
    public Optional<StaffAccount> findActiveAccount(UUID staffId) {
        return dsl.selectFrom(STAFF).where(STAFF.ID.eq(staffId).and(STAFF.ACTIVE.isTrue()).and(STAFF.PASSWORD_HASH.isNotNull()))
                .fetchOptional(StaffCredentialRepository::account);
    }

    public void setPassword(UUID staffId, String passwordHash, Instant now) {
        dsl.update(STAFF)
                .set(STAFF.PASSWORD_HASH, passwordHash)
                .set(STAFF.FAILED_LOGINS, 0)
                .setNull(STAFF.LOCKED_UNTIL)
                .set(STAFF.UPDATED_AT, now)
                .where(STAFF.ID.eq(staffId))
                .execute();
    }

    /** Counts a failure in one statement, so parallel guesses can't each see a low count. Locks at the limit and restarts it. */
    public void recordFailedLogin(UUID staffId, int limit, Instant lockUntil) {
        dsl.update(STAFF)
                .set(STAFF.LOCKED_UNTIL, DSL.when(STAFF.FAILED_LOGINS.plus(1).ge(limit), lockUntil).otherwise(STAFF.LOCKED_UNTIL))
                .set(STAFF.FAILED_LOGINS, DSL.when(STAFF.FAILED_LOGINS.plus(1).ge(limit), 0).otherwise(STAFF.FAILED_LOGINS.plus(1)))
                .where(STAFF.ID.eq(staffId))
                .execute();
    }

    public void clearFailedLogins(UUID staffId) {
        dsl.update(STAFF).set(STAFF.FAILED_LOGINS, 0).setNull(STAFF.LOCKED_UNTIL).where(STAFF.ID.eq(staffId)).execute();
    }

    private static StaffAccount account(StaffRecord r) {
        return new StaffAccount(r.getId(), r.getEmail(), r.getDisplayName(), Set.of(r.getRoles()), r.getActive(),
                r.getPasswordHash(), r.getLockedUntil());
    }
}
