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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff sign-in and accounts. The {@code /internal} routes take the staff service token, which only the desk's server
 * and the first-admin script hold: the member app's service token can't reach them. The {@code /api} routes are for
 * signed-in staff, and the account changes on them for admins.
 */
@RestController
class StaffAuthController {

    record SaveAccount(@NotBlank @Email @Size(max = 254) String email,
                       @NotBlank @Size(max = 80) String displayName,
                       @NotEmpty Set<@Pattern(regexp = "OFFICER|CHECKER|ADMIN") String> roles,
                       UUID memberId,
                       @NotBlank @Size(min = 12, max = 128) String password) {
        @Override
        public String toString() {
            return "SaveAccount[roles=" + roles + ", personal data redacted]";
        }
    }

    record SavedAccount(UUID staffId, boolean created) {
    }

    record NewAccount(UUID staffId) {
    }

    /** {@code clientAddress} is the address the desk saw, so a guesser locks only their own address out. */
    record PasswordLogin(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password,
                         @Size(max = 64) String clientAddress) {
        @Override
        public String toString() {
            return "PasswordLogin[personal data redacted]";
        }
    }

    record NewColleague(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 80) String displayName,
                        @NotEmpty Set<@Pattern(regexp = "OFFICER|CHECKER|ADMIN") String> roles,
                        UUID memberId,
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

    record AccessChange(@Size(min = 1) Set<@Pattern(regexp = "OFFICER|CHECKER|ADMIN") String> roles, Boolean active,
                        UUID linkMemberId, Boolean unlinkMember) {
    }

    record NewPassword(@NotBlank @Size(min = 12, max = 128) String password) {
        @Override
        public String toString() {
            return "NewPassword[redacted]";
        }
    }

    record CurrentAccess(UUID staffId, Set<Role> roles, boolean passwordChangeRequired) {
    }

    private final StaffLoginService logins;

    StaffAuthController(StaffLoginService logins) {
        this.logins = logins;
    }

    @PostMapping("/internal/auth/staff/accounts")
    SavedAccount saveAccount(Actor actor, @Valid @RequestBody SaveAccount body) {
        actor.requireAnyRole(Role.STAFF_ACCOUNTS);
        StaffLoginService.SavedAccount saved = logins.saveAccount(body.email(), body.displayName(), body.roles(), body.memberId(),
                body.password());
        return new SavedAccount(saved.staffId(), saved.created());
    }

    @PostMapping("/internal/auth/staff/password/login")
    StaffLoggedIn passwordLogin(Actor actor, @Valid @RequestBody PasswordLogin body) {
        actor.requireAnyRole(Role.STAFF_ACCOUNTS);
        return logins.logInWithPassword(body.email(), body.password(), body.clientAddress());
    }

    @PostMapping("/api/v1/desk/staff")
    @ResponseStatus(HttpStatus.CREATED)
    NewAccount createColleague(Actor actor, @Valid @RequestBody NewColleague body) {
        actor.requireAnyRole(Role.ADMIN);
        return new NewAccount(logins.createColleague(actor.id(), body.email(), body.displayName(), body.roles(), body.memberId(),
                body.password()));
    }

    @PatchMapping("/api/v1/desk/staff/{staffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeAccess(Actor actor, @PathVariable UUID staffId, @Valid @RequestBody AccessChange body) {
        actor.requireAnyRole(Role.ADMIN);
        logins.changeAccess(actor.id(), staffId, new StaffLoginService.AccessChange(body.roles(), body.active(),
                body.linkMemberId(), Boolean.TRUE.equals(body.unlinkMember())));
    }

    @PostMapping("/api/v1/desk/staff/{staffId}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(Actor actor, @PathVariable UUID staffId, @Valid @RequestBody NewPassword body) {
        actor.requireAnyRole(Role.ADMIN);
        logins.resetPassword(actor.id(), staffId, body.password());
    }

    @PostMapping("/api/v1/auth/staff/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(Actor actor, @Valid @RequestBody ChangePassword body) {
        logins.changePassword(staffId(actor), body.currentPassword(), body.newPassword());
    }

    @PostMapping("/api/v1/auth/staff/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logOut(Actor actor) {
        logins.endSessions(staffId(actor));
    }

    /** For the desk's server, before it uses the service token on someone's behalf: the backend can't see them then. */
    @GetMapping("/api/v1/auth/staff/me")
    CurrentAccess currentAccess(Actor actor) {
        UUID id = staffId(actor);
        return new CurrentAccess(id, actor.roles(), logins.passwordChangeRequired(id));
    }

    private static UUID staffId(Actor actor) {
        if (actor.type() != Actor.Type.STAFF) {
            throw new DomainException(CommonErrors.FORBIDDEN, "This route is for staff");
        }
        return actor.id();
    }
}
