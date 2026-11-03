package com.paypalseed.identity.infrastructure.persistence;

import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.STAFF;

import com.paypalseed.platform.security.Actor.Role;
import com.paypalseed.platform.security.StaffAccessLookup;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** Answers the security layer's question on every staff call, so a suspension or a lost role counts straight away. */
@Component
class StaffDirectoryAccess implements StaffAccessLookup {

    private final DSLContext dsl;

    StaffDirectoryAccess(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<StaffAccess> access(UUID staffId) {
        return dsl.select(STAFF.ACTIVE, STAFF.ROLES, STAFF.LINKED_MEMBER_ID, STAFF.SESSIONS_VALID_AFTER,
                        STAFF.PASSWORD_CHANGE_REQUIRED)
                .from(STAFF).where(STAFF.ID.eq(staffId))
                .fetchOptional(r -> new StaffAccess(r.value1(), roles(r.value2()), r.value3(), r.value4(), r.value5()));
    }

    private static Set<Role> roles(String[] names) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        Arrays.stream(names).map(Role::valueOf).forEach(roles::add);
        return roles;
    }
}
