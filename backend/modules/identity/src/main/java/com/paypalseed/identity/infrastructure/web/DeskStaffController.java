package com.paypalseed.identity.infrastructure.web;

import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.IdentityDtos.StaffView;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The staff directory the desk uses to show maker and checker names. */
@RestController
class DeskStaffController {

    record StaffRecord(UUID staffId, String displayName, String email, List<String> roles, boolean active) {
        @Override
        public String toString() {
            return "StaffRecord[staffId=" + staffId + ", personal data redacted]";
        }
    }

    private final IdentityFacade identity;

    DeskStaffController(IdentityFacade identity) {
        this.identity = identity;
    }

    @GetMapping("/api/v1/desk/staff")
    List<StaffRecord> list(Actor actor, @RequestParam(defaultValue = "false") boolean includeInactive) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return identity.listStaff(includeInactive).stream().map(DeskStaffController::record).toList();
    }

    static StaffRecord record(StaffView s) {
        return new StaffRecord(s.staffId(), s.displayName(), s.email(), s.roles().stream().map(Enum::name).toList(), s.active());
    }
}
