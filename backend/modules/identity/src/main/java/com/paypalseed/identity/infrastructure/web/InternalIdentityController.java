package com.paypalseed.identity.infrastructure.web;

import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.IdentityDtos.LoginResult;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.identity.api.dto.IdentityDtos.StaffUpsert;
import com.paypalseed.identity.api.dto.IdentityDtos.StaffView;
import com.paypalseed.identity.domain.model.IdentityErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-token routes for the Next.js servers: member login after Log in with PayPal, and staff upsert after staff sign-in. */
@RestController
class InternalIdentityController {

    record LoginRequest(@NotBlank @Size(max = 64) String paypalPayerId, @Email @Size(max = 254) String email,
                        @Size(max = 120) String name, Boolean verified, @Size(max = 16) String locale) {
        @Override
        public String toString() {
            return "LoginRequest[personal data redacted]";
        }
    }

    record LoginResponse(boolean created, MemberIdentityController.ProfileView member) {
    }

    record StaffRequest(@NotBlank @Size(max = 120) String displayName, @Email @Size(max = 254) String email,
                        @NotEmpty Set<String> roles, Boolean active) {
        @Override
        public String toString() {
            return "StaffRequest[personal data redacted]";
        }
    }

    private final IdentityFacade identity;

    InternalIdentityController(IdentityFacade identity) {
        this.identity = identity;
    }

    @PostMapping("/internal/members/login")
    LoginResponse login(Actor actor, @Valid @RequestBody LoginRequest body) {
        actor.requireAnyRole(Role.SERVICE);
        LoginResult result = identity.loginWithPayPal(new PayPalLogin(body.paypalPayerId(), body.email(), body.name(),
                body.verified(), body.locale()));
        return new LoginResponse(result.created(), MemberIdentityController.profile(result.member()));
    }

    @PutMapping("/internal/staff/{staffId}")
    DeskStaffController.StaffRecord upsertStaff(Actor actor, @PathVariable UUID staffId, @Valid @RequestBody StaffRequest body) {
        actor.requireAnyRole(Role.SERVICE);
        StaffView saved = identity.upsertStaff(staffId, new StaffUpsert(body.displayName(), body.email(), roles(body.roles()),
                body.active()));
        return DeskStaffController.record(saved);
    }

    private static Set<Role> roles(Set<String> names) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String name : names) {
            try {
                roles.add(Role.valueOf(name == null ? "" : name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new DomainException(IdentityErrors.INVALID_STAFF_ROLES, "Allowed roles are OFFICER, CHECKER and ADMIN");
            }
        }
        return roles;
    }
}
