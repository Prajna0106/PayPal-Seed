package com.paypalseed.identity.application.usecases;

import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.identity.domain.model.IdentityErrors;
import com.paypalseed.identity.infrastructure.persistence.MemberRepository;
import com.paypalseed.identity.infrastructure.persistence.StaffCredentialRepository;
import com.paypalseed.identity.infrastructure.persistence.StaffCredentialRepository.StaffAccount;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff accounts, passwords and desk sessions. There is no public sign-up: an admin creates colleagues, and the first
 * admin comes from the staff service token, which only the desk and the first-admin script hold.
 * Every failed login looks the same, and a hash always runs, so neither the answer nor its timing says whether an
 * email is known, suspended or locked. Failures count per source address and per account inside a window, so one
 * guesser can't lock a colleague out from everywhere. Every change to an account goes into the audit chain.
 * Lockouts and sessions run on the wall clock, not the business date, which a demo can move.
 */
@Service
public class StaffLoginService {

    public record StaffLoggedIn(UUID staffId, String displayName, Set<String> roles, boolean passwordChangeRequired) {
        @Override
        public String toString() {
            return "StaffLoggedIn[staffId=" + staffId + ", roles=" + roles + ", personal data redacted]";
        }
    }

    /** {@code created} is false when an existing account only got a new password. */
    public record SavedAccount(UUID staffId, boolean created) {
    }

    /** An admin's change to someone's access. Null fields stay as they are. */
    public record AccessChange(Set<String> roles, Boolean active, UUID linkMemberId, boolean unlinkMember) {
    }

    static final int MAX_FAILURES_PER_SOURCE = 5;
    static final int MAX_FAILURES_PER_ACCOUNT = 50;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK_FOR = Duration.ofMinutes(15);
    static final String SESSION_SOURCE = "signed-in";
    private static final String UNKNOWN_SOURCE = "unknown";
    private static final int MAX_SOURCE = 64;

    /** OWASP's minimum for Argon2id: 19 MiB, two passes, one lane. */
    private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    private final String hashOfNothing = passwords.encode("no account has this password");
    private final StaffCredentialRepository credentials;
    private final MemberRepository members;
    private final GovernanceFacade governance;
    private final TransactionTemplate transaction;
    private final Clock clock = Clock.systemUTC();

    StaffLoginService(StaffCredentialRepository credentials, MemberRepository members, GovernanceFacade governance,
                      PlatformTransactionManager transactions) {
        this.credentials = credentials;
        this.members = members;
        this.governance = governance;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * The script's route. A new email becomes an account with these roles. An existing account only gets a new password,
     * and a member link when one is given: its roles and link are never changed or cleared here.
     */
    @Transactional
    public SavedAccount saveAccount(String email, String displayName, Set<String> roles, UUID linkedMemberId, String password) {
        Instant now = now();
        Optional<StaffAccount> existing = credentials.findAnyByEmail(normalize(email));
        if (existing.isPresent() && existing.get().canLogIn()) {
            StaffAccount staff = existing.get();
            credentials.setPassword(staff.id(), passwords.encode(password), false, now);
            credentials.clearAllFailures(staff.id());
            if (linkedMemberId != null) {
                link(staff.id(), linkedMemberId, now);
            }
            audit("STAFF_PASSWORD_RESET", staff.id(), "SERVICE", "staff-accounts", "{\"linkedMember\":" + (linkedMemberId != null) + "}");
            return new SavedAccount(staff.id(), false);
        }
        String[] roleNames = sorted(roles);
        if (existing.isPresent()) {
            StaffAccount row = existing.get();
            credentials.makeAccount(row.id(), roleNames, passwords.encode(password), now);
            if (linkedMemberId != null) {
                link(row.id(), linkedMemberId, now);
            }
            audit("STAFF_ACCOUNT_CREATED", row.id(), "SERVICE", "staff-accounts", accessJson(roleNames, true, linkedMemberId != null));
            return new SavedAccount(row.id(), true);
        }
        UUID id = UUID.randomUUID();
        insert(id, email, displayName, roleNames, linkedMemberId, password, false, now);
        audit("STAFF_ACCOUNT_CREATED", id, "SERVICE", "staff-accounts", accessJson(roleNames, true, linkedMemberId != null));
        return new SavedAccount(id, true);
    }

    /** An admin creating a colleague, who must choose their own password before doing anything else. */
    @Transactional
    public UUID createColleague(UUID createdBy, String email, String displayName, Set<String> roles, UUID linkedMemberId,
                                String password) {
        UUID id = UUID.randomUUID();
        String[] roleNames = sorted(roles);
        insert(id, email, displayName, roleNames, linkedMemberId, password, true, now());
        audit("STAFF_ACCOUNT_CREATED", id, "STAFF", createdBy.toString(), accessJson(roleNames, true, linkedMemberId != null));
        return id;
    }

    /** A wrong current password counts towards the lockout, so a stolen session can't be used to guess it. */
    public void changePassword(UUID staffId, String currentPassword, String newPassword) {
        StaffAccount staff = credentials.findActiveAccount(staffId)
                .orElseThrow(() -> new DomainException(IdentityErrors.STAFF_ACCOUNT_REQUIRED, IdentityErrors.STAFF_ACCOUNT_REQUIRED.title()));
        Instant now = now();
        boolean matches = passwords.matches(currentPassword, staff.passwordHash());
        if (credentials.locked(staffId, SESSION_SOURCE, now)) {
            throw new DomainException(IdentityErrors.CURRENT_PASSWORD_WRONG, IdentityErrors.CURRENT_PASSWORD_WRONG.title());
        }
        if (!matches) {
            recordFailure(staffId, SESSION_SOURCE, now);
            throw new DomainException(IdentityErrors.CURRENT_PASSWORD_WRONG, IdentityErrors.CURRENT_PASSWORD_WRONG.title());
        }
        if (passwords.matches(newPassword, staff.passwordHash())) {
            throw new DomainException(IdentityErrors.PASSWORD_UNCHANGED, IdentityErrors.PASSWORD_UNCHANGED.title());
        }
        String hash = passwords.encode(newPassword);
        transaction.executeWithoutResult(status -> {
            credentials.setPassword(staffId, hash, false, now);
            credentials.clearAllFailures(staffId);
            audit("STAFF_PASSWORD_CHANGED", staffId, "STAFF", staffId.toString(), "{}");
        });
    }

    /** An admin choosing a new starting password for a colleague. Their sessions end and they must change it. */
    @Transactional
    public void resetPassword(UUID adminId, UUID staffId, String password) {
        StaffAccount staff = credentials.find(staffId).filter(StaffAccount::canLogIn)
                .orElseThrow(() -> new DomainException(IdentityErrors.STAFF_ACCOUNT_REQUIRED, IdentityErrors.STAFF_ACCOUNT_REQUIRED.title()));
        credentials.setPassword(staff.id(), passwords.encode(password), true, now());
        credentials.clearAllFailures(staff.id());
        audit("STAFF_PASSWORD_RESET", staff.id(), "STAFF", adminId.toString(), "{\"linkedMember\":false}");
    }

    /**
     * An admin changing roles, suspension or the member link. Nobody can take away their own admin role or suspend
     * themselves, and the last active admin can't be removed, so the desk can never be left with no admin.
     */
    @Transactional
    public void changeAccess(UUID adminId, UUID staffId, AccessChange change) {
        StaffAccount staff = credentials.find(staffId)
                .orElseThrow(() -> new DomainException(IdentityErrors.STAFF_NOT_FOUND, IdentityErrors.STAFF_NOT_FOUND.title()));
        Set<String> roles = change.roles() == null ? staff.roles() : change.roles();
        boolean active = change.active() == null ? staff.active() : change.active();
        UUID linked = change.unlinkMember() ? null : change.linkMemberId() != null ? change.linkMemberId() : staff.linkedMemberId();
        if (roles.isEmpty()) {
            throw new DomainException(IdentityErrors.INVALID_STAFF_ROLES, IdentityErrors.INVALID_STAFF_ROLES.title());
        }
        boolean staysAdmin = active && roles.contains("ADMIN");
        if (staffId.equals(adminId) && !staysAdmin) {
            throw new DomainException(IdentityErrors.STAFF_SELF_LOCKOUT, IdentityErrors.STAFF_SELF_LOCKOUT.title());
        }
        if (!staysAdmin && staff.active() && staff.roles().contains("ADMIN") && credentials.otherActiveAdmins(staffId) == 0) {
            throw new DomainException(IdentityErrors.STAFF_LAST_ADMIN, IdentityErrors.STAFF_LAST_ADMIN.title());
        }
        if (linked != null && !linked.equals(staff.linkedMemberId())) {
            checkLinkable(linked, staffId);
        }
        String[] roleNames = sorted(roles);
        credentials.updateAccess(staffId, roleNames, active, linked, now());
        audit("STAFF_ACCESS_CHANGED", staffId, "STAFF", adminId.toString(), "{\"before\":"
                + accessJson(sorted(staff.roles()), staff.active(), staff.linkedMemberId() != null)
                + ",\"after\":" + accessJson(roleNames, active, linked != null) + "}");
    }

    /** Logging out ends every desk session of this person, so a copied session cookie stops working too. */
    public void endSessions(UUID staffId) {
        credentials.findActiveAccount(staffId).ifPresent(staff -> credentials.endSessions(staff.id(), now()));
    }

    public boolean passwordChangeRequired(UUID staffId) {
        return credentials.find(staffId).map(StaffAccount::passwordChangeRequired).orElse(false);
    }

    /** {@code source} is the client address the desk saw; anything else shares one bucket. */
    public StaffLoggedIn logInWithPassword(String email, String password, String source) {
        Optional<StaffAccount> account = credentials.findByEmail(normalize(email));
        String hash = account.map(StaffAccount::passwordHash).orElse(hashOfNothing);
        boolean matches = passwords.matches(password, hash);
        Instant now = now();
        if (account.isEmpty()) {
            throw refused();
        }
        StaffAccount staff = account.get();
        String from = source(source);
        if (credentials.locked(staff.id(), from, now)) {
            throw refused();
        }
        if (!matches) {
            recordFailure(staff.id(), from, now);
            throw refused();
        }
        if (!staff.active() || staff.roles().isEmpty()) {
            throw refused();
        }
        credentials.clearFailures(staff.id(), from);
        return new StaffLoggedIn(staff.id(), staff.displayName(), staff.roles(), staff.passwordChangeRequired());
    }

    private void recordFailure(UUID staffId, String source, Instant now) {
        Instant windowStart = now.minus(WINDOW);
        Instant lockUntil = now.plus(LOCK_FOR);
        credentials.recordFailure(staffId, source, MAX_FAILURES_PER_SOURCE, windowStart, lockUntil, now);
        credentials.recordFailure(staffId, StaffCredentialRepository.ANY_SOURCE, MAX_FAILURES_PER_ACCOUNT, windowStart, lockUntil, now);
    }

    private void insert(UUID id, String email, String displayName, String[] roles, UUID linkedMemberId, String password,
                        boolean passwordChangeRequired, Instant now) {
        if (linkedMemberId != null) {
            checkLinkable(linkedMemberId, null);
        }
        if (!credentials.insert(id, normalize(email), displayName, roles, linkedMemberId, passwords.encode(password),
                passwordChangeRequired, now)) {
            throw new DomainException(IdentityErrors.STAFF_EMAIL_TAKEN, IdentityErrors.STAFF_EMAIL_TAKEN.title());
        }
    }

    private void link(UUID staffId, UUID memberId, Instant now) {
        checkLinkable(memberId, staffId);
        credentials.link(staffId, memberId, now);
    }

    private void checkLinkable(UUID memberId, UUID staffId) {
        if (members.find(memberId).isEmpty()) {
            throw new DomainException(IdentityErrors.MEMBER_NOT_FOUND, "No member " + memberId);
        }
        if (credentials.memberLinkedElsewhere(memberId, staffId)) {
            throw new DomainException(IdentityErrors.STAFF_MEMBER_LINKED, IdentityErrors.STAFF_MEMBER_LINKED.title());
        }
    }

    private void audit(String entryType, UUID staffId, String actorType, String actorId, String payloadJson) {
        governance.audit(new AuditCommand(entryType, staffId, actorType, actorId, payloadJson));
    }

    /** Roles, active and whether a member account is linked: never names, emails or the member id. */
    private static String accessJson(String[] roles, boolean active, boolean linkedMember) {
        String names = java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").collect(Collectors.joining(","));
        return "{\"roles\":[" + names + "],\"active\":" + active + ",\"linkedMember\":" + linkedMember + "}";
    }

    private static String[] sorted(Set<String> roles) {
        return new TreeSet<>(roles).toArray(String[]::new);
    }

    private static String source(String source) {
        if (source == null || source.isBlank()) {
            return UNKNOWN_SOURCE;
        }
        String trimmed = source.trim();
        return trimmed.equals(StaffCredentialRepository.ANY_SOURCE) || trimmed.equals(SESSION_SOURCE) ? UNKNOWN_SOURCE
                : trimmed.substring(0, Math.min(trimmed.length(), MAX_SOURCE));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    static DomainException refused() {
        return new DomainException(IdentityErrors.STAFF_LOGIN_REFUSED, IdentityErrors.STAFF_LOGIN_REFUSED.title());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
