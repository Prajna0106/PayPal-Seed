package com.paypalseed.identity.domain.model;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor.Role;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** A person who works the desk. Roles come from the platform role enum; only the three staff roles are allowed. */
public record StaffMember(UUID id, String displayName, String email, Set<Role> roles, boolean active, Instant createdAt,
                          Instant updatedAt) {

    public static final Set<Role> STAFF_ROLES = Set.copyOf(EnumSet.of(Role.OFFICER, Role.CHECKER, Role.ADMIN));
    public static final int MAX_NAME = 120;
    public static final int MAX_EMAIL = 254;

    public StaffMember {
        roles = Set.copyOf(EnumSet.copyOf(roles));
    }

    /** Validates the values a caller sent and builds the record. Returns the stored row unchanged apart from these values. */
    public static StaffMember of(UUID id, String displayName, String email, Set<Role> roles, Boolean active, Instant createdAt,
                                 Instant now) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            throw new DomainException(IdentityErrors.INVALID_NAME, "The staff name must not be empty");
        }
        if (roles == null || roles.isEmpty() || !STAFF_ROLES.containsAll(roles)) {
            throw new DomainException(IdentityErrors.INVALID_STAFF_ROLES, "Allowed roles are OFFICER, CHECKER and ADMIN");
        }
        String mail = email == null || email.isBlank() ? null : email.trim();
        return new StaffMember(id, name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name,
                mail != null && mail.length() > MAX_EMAIL ? mail.substring(0, MAX_EMAIL) : mail, roles,
                active == null || active, createdAt, now);
    }

    /** Never prints personal data. */
    @Override
    public String toString() {
        return "StaffMember[id=" + id + ", roles=" + roles + ", active=" + active + ", personal data redacted]";
    }
}
