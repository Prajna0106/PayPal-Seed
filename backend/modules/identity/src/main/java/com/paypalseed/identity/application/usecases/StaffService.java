package com.paypalseed.identity.application.usecases;

import com.paypalseed.identity.api.dto.IdentityDtos.StaffUpsert;
import com.paypalseed.identity.api.dto.IdentityDtos.StaffView;
import com.paypalseed.identity.domain.model.StaffMember;
import com.paypalseed.identity.infrastructure.persistence.StaffRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.security.Actor.Role;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The desk staff directory. Names only matter to people, so nothing here publishes events. */
@Service
public class StaffService {

    private final StaffRepository staff;
    private final BusinessClock clock;

    StaffService(StaffRepository staff, BusinessClock clock) {
        this.staff = staff;
        this.clock = clock;
    }

    @Transactional
    public StaffView upsert(UUID staffId, StaffUpsert command) {
        Instant now = now();
        StaffMember member = StaffMember.of(staffId, command.displayName(), command.email(), command.roles(), command.active(),
                now, now);
        staff.upsert(member);
        return staff.find(staffId).map(StaffService::view).orElseThrow();
    }

    /** Adds the staff member only if the id is new, so a restart never undoes an edit. True when it was added. */
    @Transactional
    public boolean seed(UUID staffId, String displayName, Set<Role> roles) {
        Instant now = now();
        return staff.insertIfAbsent(StaffMember.of(staffId, displayName, null, roles, true, now, now));
    }

    @Transactional(readOnly = true)
    public Optional<StaffView> find(UUID staffId) {
        return staff.find(staffId).map(StaffService::view);
    }

    @Transactional(readOnly = true)
    public List<StaffView> list(boolean includeInactive) {
        return staff.list(includeInactive).stream().map(StaffService::view).toList();
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> displayNames(Collection<UUID> staffIds) {
        Map<UUID, String> names = new HashMap<>();
        staff.findAll(staffIds.stream().filter(Objects::nonNull).distinct().toList())
                .forEach(s -> names.put(s.id(), s.displayName()));
        return Map.copyOf(names);
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static StaffView view(StaffMember s) {
        return new StaffView(s.id(), s.displayName(), s.email(), s.roles().stream().sorted().toList(), s.active());
    }
}
