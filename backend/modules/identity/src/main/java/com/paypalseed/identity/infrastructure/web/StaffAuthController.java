package com.paypalseed.identity.infrastructure.web;

import com.paypalseed.identity.application.usecases.StaffLoginService;
import com.paypalseed.identity.application.usecases.StaffLoginService.StaffLoggedIn;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff sign-in. The {@code /internal} routes take the service token, so only the desk's server and the first-admin
 * script can reach them. The {@code /api} routes are for signed-in staff.
 */
@RestController
class StaffAuthController {

    record SaveAccount(@NotBlank @Email @Size(max = 254) String email,
                       @NotBlank @Size(max = 80) String displayName,
                       @NotEmpty Set<@Pattern(regexp = "OFFICER|CHECKER|ADMIN") String> roles,
                       UUID memberId,
                       @Size(min = 12, max = 128) String password) {
        @Override
        public String toString() {
            return "SaveAccount[roles=" + roles + ", personal data redacted]";
        }
    }

    record SavedAccount(UUID staffId) {
    }

    record PasswordLogin(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "PasswordLogin[personal data redacted]";
        }
    }

    record NewColleague(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 80) String displayName,
                        @NotEmpty Set<@Pattern(regexp = "OFFICER|CHECKER|ADMIN") String> roles,
                        @NotBlank @Size(min = 12, max = 128) String password) {
        @Override
        public String toString() {
            return "NewColleague[roles=" + roles + ", personal data redacted]";
        }
    }

    record ChangePassword(@NotBlank @Size(max = 128) String currentPassword,
                          @NotBlank @Size(min = 12, max = 128) String newPassword) {
        @Override
        public String toString() {
            return "ChangePassword[redacted]";
        }
    }

    record CurrentAccess(UUID staffId, Set<Role> roles) {
    }

    private final StaffLoginService logins;

    StaffAuthController(StaffLoginService logins) {
        this.logins = logins;
    }

    @PostMapping("/internal/auth/staff/accounts")
    SavedAccount saveAccount(Actor actor, @Valid @RequestBody SaveAccount body) {
        actor.requireAnyRole(Role.SERVICE);
        return new SavedAccount(logins.saveAccount(body.email(), body.displayName(), body.roles(), body.memberId(), body.password()));
    }

    @PostMapping("/internal/auth/staff/password/login")
    StaffLoggedIn passwordLogin(Actor actor, @Valid @RequestBody PasswordLogin body) {
        actor.requireAnyRole(Role.SERVICE);
        return logins.logInWithPassword(body.email(), body.password());
    }

    /** Admins only: anyone else could create themselves a stronger account. */
    @PostMapping("/api/v1/desk/staff")
    @ResponseStatus(HttpStatus.CREATED)
    SavedAccount createColleague(Actor actor, @Valid @RequestBody NewColleague body) {
        actor.requireAnyRole(Role.ADMIN);
        return new SavedAccount(logins.createColleague(actor.id(), body.email(), body.displayName(), body.roles(), body.password()));
    }

    @PostMapping("/api/v1/auth/staff/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(Actor actor, @Valid @RequestBody ChangePassword body) {
        logins.changePassword(staffId(actor), body.currentPassword(), body.newPassword());
    }

    /** For the desk's server, before it uses the service token on someone's behalf: the backend can't see them then. */
    @GetMapping("/api/v1/auth/staff/me")
    CurrentAccess currentAccess(Actor actor) {
        return new CurrentAccess(staffId(actor), actor.roles());
    }

    private static UUID staffId(Actor actor) {
        if (actor.type() != Actor.Type.STAFF) {
            throw new DomainException(CommonErrors.FORBIDDEN, "This route is for staff");
        }
        return actor.id();
    }
}
