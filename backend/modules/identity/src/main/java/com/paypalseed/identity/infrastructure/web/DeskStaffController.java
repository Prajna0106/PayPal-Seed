package com.paypalseed.identity.infrastructure.web;

import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.IdentityDtos.StaffView;
import com.paypalseed.identity.infrastructure.persistence.StaffCredentialRepository;
import com.paypalseed.identity.infrastructure.persistence.StaffCredentialRepository.LoginFacts;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The staff directory the desk uses to show maker and checker names. */
@RestController
class DeskStaffController {

    /** {@code linkedMemberId} and {@code passwordChangeRequired} are filled in for admins only. */
    record StaffRecord(UUID staffId, String displayName, String email, List<String> roles, boolean active, boolean canLogIn,
                       UUID linkedMemberId, Boolean passwordChangeRequired) {
        @Override
        public String toString() {
            return "StaffRecord[staffId=" + staffId + ", personal data redacted]";
        }
    }

    private final IdentityFacade identity;
    private final StaffCredentialRepository credentials;

    DeskStaffController(IdentityFacade identity, StaffCredentialRepository credentials) {
        this.identity = identity;
        this.credentials = credentials;
    }

    @GetMapping("/api/v1/desk/staff")
    List<StaffRecord> list(Actor actor, @RequestParam(defaultValue = "false") boolean includeInactive) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        boolean admin = actor.roles().contains(Role.ADMIN);
        Map<UUID, LoginFacts> facts = credentials.loginFacts();
        return identity.listStaff(includeInactive).stream().map(s -> record(s, facts.get(s.staffId()), admin)).toList();
    }

    static StaffRecord record(StaffView s) {
        return record(s, null, false);
    }

    private static StaffRecord record(StaffView s, LoginFacts facts, boolean admin) {
        return new StaffRecord(s.staffId(), s.displayName(), s.email(), s.roles().stream().map(Enum::name).toList(), s.active(),
                facts != null && facts.canLogIn(), admin && facts != null ? facts.linkedMemberId() : null,
                admin && facts != null ? facts.passwordChangeRequired() : null);
    }
}
