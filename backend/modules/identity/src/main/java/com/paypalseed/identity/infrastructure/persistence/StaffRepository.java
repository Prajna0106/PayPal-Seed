package com.paypalseed.identity.infrastructure.persistence;

import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.STAFF;

import com.paypalseed.identity.domain.model.StaffMember;
import com.paypalseed.identity.infrastructure.persistence.jooq.tables.records.StaffRecord;
import com.paypalseed.platform.security.Actor.Role;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class StaffRepository {

    private final DSLContext dsl;

    StaffRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Creates the row or replaces everything except its creation time. */
    public void upsert(StaffMember s) {
        dsl.insertInto(STAFF)
                .set(STAFF.ID, s.id())
                .set(STAFF.DISPLAY_NAME, s.displayName())
                .set(STAFF.EMAIL, s.email())
                .set(STAFF.ROLES, roleNames(s.roles()))
                .set(STAFF.ACTIVE, s.active())
                .set(STAFF.CREATED_AT, s.createdAt())
                .set(STAFF.UPDATED_AT, s.updatedAt())
                .onConflict(STAFF.ID)
                .doUpdate()
                .set(STAFF.DISPLAY_NAME, s.displayName())
                .set(STAFF.EMAIL, s.email())
                .set(STAFF.ROLES, roleNames(s.roles()))
                .set(STAFF.ACTIVE, s.active())
                .set(STAFF.UPDATED_AT, s.updatedAt())
                .execute();
    }

    /** False when the staff member already exists; used by demo seeding so later edits are kept. */
    public boolean insertIfAbsent(StaffMember s) {
        return dsl.insertInto(STAFF)
                .set(STAFF.ID, s.id())
                .set(STAFF.DISPLAY_NAME, s.displayName())
                .set(STAFF.EMAIL, s.email())
                .set(STAFF.ROLES, roleNames(s.roles()))
                .set(STAFF.ACTIVE, s.active())
                .set(STAFF.CREATED_AT, s.createdAt())
                .set(STAFF.UPDATED_AT, s.updatedAt())
                .onConflictDoNothing()
                .execute() == 1;
    }

    public Optional<StaffMember> find(UUID id) {
        return dsl.selectFrom(STAFF).where(STAFF.ID.eq(id)).fetchOptional(StaffRepository::map);
    }

    public List<StaffMember> list(boolean includeInactive) {
        var query = dsl.selectFrom(STAFF);
        return (includeInactive ? query : query.where(STAFF.ACTIVE.isTrue())).orderBy(STAFF.DISPLAY_NAME, STAFF.ID)
                .fetch(StaffRepository::map);
    }

    public List<StaffMember> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(STAFF).where(STAFF.ID.in(ids)).fetch(StaffRepository::map);
    }

    private static String[] roleNames(Set<Role> roles) {
        return roles.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    private static StaffMember map(StaffRecord r) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        Arrays.stream(r.getRoles()).map(Role::valueOf).forEach(roles::add);
        return new StaffMember(r.getId(), r.getDisplayName(), r.getEmail(), roles, r.getActive(), r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
