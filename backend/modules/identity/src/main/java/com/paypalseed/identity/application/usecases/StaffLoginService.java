package com.paypalseed.identity.application.usecases;

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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Staff accounts and passwords. There is no public sign-up: an admin creates colleagues, and the first admin comes from
 * the service token.
 * Every failed login looks the same, and a hash always runs, so neither the answer nor its timing says whether an
 * email is known, suspended or locked. The desk's server turns a success into its session and signs the actor tokens.
 * Lockouts run on the wall clock, not the business date, which a demo can move.
 */
@Service
public class StaffLoginService {

    public record StaffLoggedIn(UUID staffId, String displayName, Set<String> roles) {
        @Override
        public String toString() {
            return "StaffLoggedIn[staffId=" + staffId + ", roles=" + roles + ", personal data redacted]";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(StaffLoginService.class);
    static final int MAX_FAILED_LOGINS = 5;
    static final Duration LOCK_FOR = Duration.ofMinutes(15);

    /** OWASP's minimum for Argon2id: 19 MiB, two passes, one lane. */
    private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    private final String hashOfNothing = passwords.encode("no account has this password");
    private final StaffCredentialRepository credentials;
    private final MemberRepository members;
    private final Clock clock = Clock.systemUTC();

    StaffLoginService(StaffCredentialRepository credentials, MemberRepository members) {
        this.credentials = credentials;
        this.members = members;
    }

    /** Creates or updates an account. A null password leaves the current one. */
    public UUID saveAccount(String email, String displayName, Set<String> roles, UUID linkedMemberId, String password) {
        if (linkedMemberId != null && members.find(linkedMemberId).isEmpty()) {
            throw new DomainException(IdentityErrors.MEMBER_NOT_FOUND, "No member " + linkedMemberId);
        }
        return credentials.saveAccount(UUID.randomUUID(), normalize(email), displayName, roles.toArray(String[]::new),
                linkedMemberId, password == null ? null : passwords.encode(password),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /** An admin creating a colleague. Unlike {@link #saveAccount}, this refuses an email that is already taken. */
    public UUID createColleague(UUID createdBy, String email, String displayName, Set<String> roles, String password) {
        UUID id = UUID.randomUUID();
        if (!credentials.insertNew(id, normalize(email), displayName, roles.toArray(String[]::new), passwords.encode(password),
                clock.instant().truncatedTo(ChronoUnit.MICROS))) {
            throw new DomainException(IdentityErrors.STAFF_EMAIL_TAKEN, IdentityErrors.STAFF_EMAIL_TAKEN.title());
        }
        log.info("Staff account {} created by {} with roles {}", id, createdBy, roles);
        return id;
    }

    /** A wrong current password counts towards the lockout, so a stolen session can't be used to guess it. */
    public void changePassword(UUID staffId, String currentPassword, String newPassword) {
        StaffAccount staff = credentials.findActiveAccount(staffId)
                .orElseThrow(() -> new DomainException(IdentityErrors.STAFF_ACCOUNT_REQUIRED, IdentityErrors.STAFF_ACCOUNT_REQUIRED.title()));
        Instant now = clock.instant();
        boolean matches = passwords.matches(currentPassword, staff.passwordHash());
        if (staff.lockedUntil() != null && now.isBefore(staff.lockedUntil())) {
            throw new DomainException(IdentityErrors.CURRENT_PASSWORD_WRONG, IdentityErrors.CURRENT_PASSWORD_WRONG.title());
        }
        if (!matches) {
            credentials.recordFailedLogin(staffId, MAX_FAILED_LOGINS, now.plus(LOCK_FOR).truncatedTo(ChronoUnit.MICROS));
            throw new DomainException(IdentityErrors.CURRENT_PASSWORD_WRONG, IdentityErrors.CURRENT_PASSWORD_WRONG.title());
        }
        credentials.setPassword(staffId, passwords.encode(newPassword), now.truncatedTo(ChronoUnit.MICROS));
    }

    public StaffLoggedIn logInWithPassword(String email, String password) {
        Optional<StaffAccount> account = credentials.findByEmail(normalize(email));
        String hash = account.map(StaffAccount::passwordHash).orElse(hashOfNothing);
        boolean matches = passwords.matches(password, hash);
        Instant now = clock.instant();
        if (account.isEmpty()) {
            throw refused();
        }
        StaffAccount staff = account.get();
        if (staff.lockedUntil() != null && now.isBefore(staff.lockedUntil())) {
            throw refused();
        }
        if (!matches) {
            credentials.recordFailedLogin(staff.id(), MAX_FAILED_LOGINS, now.plus(LOCK_FOR).truncatedTo(ChronoUnit.MICROS));
            throw refused();
        }
        if (!staff.active() || staff.roles().isEmpty()) {
            throw refused();
        }
        credentials.clearFailedLogins(staff.id());
        return loggedIn(staff);
    }

    static StaffLoggedIn loggedIn(StaffAccount staff) {
        return new StaffLoggedIn(staff.id(), staff.displayName(), staff.roles());
    }

    static DomainException refused() {
        return new DomainException(IdentityErrors.STAFF_LOGIN_REFUSED, IdentityErrors.STAFF_LOGIN_REFUSED.title());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
