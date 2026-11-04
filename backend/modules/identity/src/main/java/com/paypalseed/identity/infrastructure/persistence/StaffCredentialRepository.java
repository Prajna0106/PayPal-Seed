package com.paypalseed.identity.infrastructure.persistence;

import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.STAFF;
import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.STAFF_LOGIN_FAILURE;

import com.paypalseed.identity.infrastructure.persistence.jooq.tables.records.StaffRecord;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Staff passwords, desk sessions and failed logins. The directory itself (names, roles, active) stays in {@link StaffRepository}. */
@Repository
public class StaffCredentialRepository {

    /** Account-wide failures, counted across every source address. */
    public static final String ANY_SOURCE = "*";

    public record StaffAccount(UUID id, String email, String displayName, Set<String> roles, boolean active,
                               String passwordHash, UUID linkedMemberId, boolean passwordChangeRequired) {
        /** Never prints the hash or personal data. */
        @Override
        public String toString() {
            return "StaffAccount[id=" + id + ", roles=" + roles + ", active=" + active + ", personal data redacted]";
        }

        public boolean canLogIn() {
            return passwordHash != null;
        }
    }

    /** What the Staff page shows beyond the directory row. */
    public record LoginFacts(boolean canLogIn, UUID linkedMemberId, boolean passwordChangeRequired) {
    }

    private final DSLContext dsl;

    StaffCredentialRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The row with this email, preferring one that already has a password. */
    public Optional<StaffAccount> findAnyByEmail(String email) {
        return dsl.selectFrom(STAFF).where(DSL.lower(STAFF.EMAIL).eq(email))
                .orderBy(STAFF.PASSWORD_HASH.isNull(), STAFF.ID).limit(1)
                .fetchOptional(StaffCredentialRepository::account);
    }

    public Optional<StaffAccount> findByEmail(String email) {
        return dsl.selectFrom(STAFF).where(DSL.lower(STAFF.EMAIL).eq(email).and(STAFF.PASSWORD_HASH.isNotNull()))
                .fetchOptional(StaffCredentialRepository::account);
    }

    public Optional<StaffAccount> find(UUID staffId) {
        return dsl.selectFrom(STAFF).where(STAFF.ID.eq(staffId)).fetchOptional(StaffCredentialRepository::account);
    }

    /** An account someone can log in to and that isn't suspended. Demo personas have no password, so they aren't one. */
    public Optional<StaffAccount> findActiveAccount(UUID staffId) {
        return dsl.selectFrom(STAFF).where(STAFF.ID.eq(staffId).and(STAFF.ACTIVE.isTrue()).and(STAFF.PASSWORD_HASH.isNotNull()))
                .fetchOptional(StaffCredentialRepository::account);
    }

    /** False when the email or the member account already belongs to someone. */
    public boolean insert(UUID id, String email, String displayName, String[] roles, UUID linkedMemberId, String passwordHash,
                          boolean passwordChangeRequired, Instant now) {
        return dsl.insertInto(STAFF)
                .set(STAFF.ID, id)
                .set(STAFF.EMAIL, email)
                .set(STAFF.DISPLAY_NAME, displayName)
                .set(STAFF.ROLES, roles)
                .set(STAFF.LINKED_MEMBER_ID, linkedMemberId)
                .set(STAFF.PASSWORD_HASH, passwordHash)
                .set(STAFF.PASSWORD_CHANGE_REQUIRED, passwordChangeRequired)
                .set(STAFF.CREATED_AT, now)
                .set(STAFF.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute() == 1;
    }

    /** Gives a directory row with no password (such as a demo persona) its first password and roles. */
    public void makeAccount(UUID staffId, String[] roles, String passwordHash, Instant now) {
        dsl.update(STAFF)
                .set(STAFF.ROLES, roles)
                .set(STAFF.PASSWORD_HASH, passwordHash)
                .set(STAFF.PASSWORD_CHANGE_REQUIRED, false)
                .set(STAFF.SESSIONS_VALID_AFTER, now)
                .set(STAFF.UPDATED_AT, now)
                .where(STAFF.ID.eq(staffId))
                .execute();
    }

    /** A new password ends every desk session that began before it. */
    public void setPassword(UUID staffId, String passwordHash, boolean passwordChangeRequired, Instant now) {
        dsl.update(STAFF)
                .set(STAFF.PASSWORD_HASH, passwordHash)
                .set(STAFF.PASSWORD_CHANGE_REQUIRED, passwordChangeRequired)
                .set(STAFF.SESSIONS_VALID_AFTER, now)
                .set(STAFF.UPDATED_AT, now)
                .where(STAFF.ID.eq(staffId))
                .execute();
    }

    public void endSessions(UUID staffId, Instant now) {
        dsl.update(STAFF).set(STAFF.SESSIONS_VALID_AFTER, now).where(STAFF.ID.eq(staffId)).execute();
    }

    public void updateAccess(UUID staffId, String[] roles, boolean active, UUID linkedMemberId, Instant now) {
        dsl.update(STAFF)
                .set(STAFF.ROLES, roles)
                .set(STAFF.ACTIVE, active)
                .set(STAFF.LINKED_MEMBER_ID, linkedMemberId)
                .set(STAFF.UPDATED_AT, now)
                .where(STAFF.ID.eq(staffId))
                .execute();
    }

    public void link(UUID staffId, UUID memberId, Instant now) {
        dsl.update(STAFF).set(STAFF.LINKED_MEMBER_ID, memberId).set(STAFF.UPDATED_AT, now).where(STAFF.ID.eq(staffId)).execute();
    }

    /** Whether another staff row is already linked to this member account. */
    public boolean memberLinkedElsewhere(UUID memberId, UUID staffId) {
        return dsl.fetchExists(dsl.selectOne().from(STAFF)
                .where(STAFF.LINKED_MEMBER_ID.eq(memberId).and(staffId == null ? DSL.trueCondition() : STAFF.ID.ne(staffId))));
    }

    /** Active staff holding ADMIN, other than {@code except}. */
    public int otherActiveAdmins(UUID except) {
        return dsl.fetchCount(STAFF, STAFF.ACTIVE.isTrue().and(STAFF.ID.ne(except))
                .and(DSL.condition("{0} = any({1})", DSL.inline("ADMIN"), STAFF.ROLES)));
    }

    public Map<UUID, LoginFacts> loginFacts() {
        return dsl.select(STAFF.ID, STAFF.PASSWORD_HASH.isNotNull(), STAFF.LINKED_MEMBER_ID, STAFF.PASSWORD_CHANGE_REQUIRED)
                .from(STAFF)
                .fetch()
                .stream()
                .collect(Collectors.toMap(r -> r.value1(), r -> new LoginFacts(r.value2(), r.value3(), r.value4())));
    }

    /** Whether this source, or the account as a whole, is locked out right now. */
    public boolean locked(UUID staffId, String source, Instant now) {
        return dsl.fetchExists(dsl.selectOne().from(STAFF_LOGIN_FAILURE)
                .where(STAFF_LOGIN_FAILURE.STAFF_ID.eq(staffId)
                        .and(STAFF_LOGIN_FAILURE.SOURCE.in(source, ANY_SOURCE))
                        .and(STAFF_LOGIN_FAILURE.LOCKED_UNTIL.gt(now))));
    }

    /**
     * Counts one failure in a single statement, so parallel guesses can't each see a low count. Failures older than the
     * window start a fresh count; reaching {@code limit} inside the window locks until {@code lockUntil}.
     */
    public void recordFailure(UUID staffId, String source, int limit, Instant windowStart, Instant lockUntil, Instant now) {
        var f = STAFF_LOGIN_FAILURE;
        var fresh = f.WINDOW_STARTED_AT.le(windowStart);
        dsl.insertInto(f)
                .set(f.STAFF_ID, staffId)
                .set(f.SOURCE, source)
                .set(f.FAILURES, 1)
                .set(f.WINDOW_STARTED_AT, now)
                .set(f.LOCKED_UNTIL, limit <= 1 ? lockUntil : null)
                .onConflict(f.STAFF_ID, f.SOURCE)
                .doUpdate()
                .set(f.FAILURES, DSL.when(fresh, DSL.inline(1)).otherwise(f.FAILURES.plus(1)))
                .set(f.WINDOW_STARTED_AT, DSL.when(fresh, DSL.val(now)).otherwise(f.WINDOW_STARTED_AT))
                .set(f.LOCKED_UNTIL, DSL.when(fresh.not().and(f.FAILURES.plus(1).ge(limit)), DSL.val(lockUntil))
                        .otherwise(f.LOCKED_UNTIL))
                .execute();
    }

    public void clearFailures(UUID staffId, String source) {
        dsl.deleteFrom(STAFF_LOGIN_FAILURE)
                .where(STAFF_LOGIN_FAILURE.STAFF_ID.eq(staffId).and(STAFF_LOGIN_FAILURE.SOURCE.eq(source)))
                .execute();
    }

    public void clearAllFailures(UUID staffId) {
        dsl.deleteFrom(STAFF_LOGIN_FAILURE).where(STAFF_LOGIN_FAILURE.STAFF_ID.eq(staffId)).execute();
    }

    private static StaffAccount account(StaffRecord r) {
        return new StaffAccount(r.getId(), r.getEmail(), r.getDisplayName(), Set.of(r.getRoles()), r.getActive(),
                r.getPasswordHash(), r.getLinkedMemberId(), r.getPasswordChangeRequired());
    }
}
