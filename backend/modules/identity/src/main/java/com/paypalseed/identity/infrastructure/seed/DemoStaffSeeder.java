package com.paypalseed.identity.infrastructure.seed;

import com.paypalseed.identity.application.usecases.StaffService;
import com.paypalseed.platform.security.Actor.Role;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Inserts the three demo staff whose ids the desk app already uses for its personas. Runs only under the {@code demo}
 * and {@code dev} profiles, and only adds rows that are missing, so a restart never undoes an edit.
 */
@Component
@Profile({"demo", "dev"})
class DemoStaffSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoStaffSeeder.class);

    record DemoStaff(UUID id, String name, Set<Role> roles) {
    }

    static final Set<DemoStaff> STAFF = Set.of(
            new DemoStaff(UUID.fromString("0b5e1f4a-6d2c-4c7e-9a1b-3f6e2d8c4a01"), "Aira Jena", Set.of(Role.OFFICER)),
            new DemoStaff(UUID.fromString("0b5e1f4a-6d2c-4c7e-9a1b-3f6e2d8c4a02"), "Priyam Priyanshu Pradhan",
                    Set.of(Role.CHECKER)),
            new DemoStaff(UUID.fromString("0b5e1f4a-6d2c-4c7e-9a1b-3f6e2d8c4a03"), "Prajna Jeet Ojha",
                    Set.of(Role.OFFICER, Role.CHECKER, Role.ADMIN)));

    private final StaffService staff;

    DemoStaffSeeder(StaffService staff) {
        this.staff = staff;
    }

    @Override
    public void run(ApplicationArguments args) {
        long added = STAFF.stream().filter(s -> staff.seed(s.id(), s.name(), s.roles())).count();
        log.info("Demo staff ready ({} added)", added);
    }
}
