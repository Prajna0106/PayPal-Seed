package com.paypalseed.identity.infrastructure.web;

import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentGrant;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentView;
import com.paypalseed.identity.api.dto.IdentityDtos.GrantResult;
import com.paypalseed.identity.api.dto.IdentityDtos.MemberView;
import com.paypalseed.identity.api.dto.IdentityDtos.ProfileUpdate;
import com.paypalseed.identity.api.dto.Language;
import com.paypalseed.identity.api.dto.WalletPreference;
import com.paypalseed.identity.domain.model.IdentityErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member routes. Every route reads the member id from the signed-in actor and never from the URL or body, so one
 * member cannot read or change another member's profile or consents.
 */
@RestController
class MemberIdentityController {

    record ProfileView(UUID memberId, String email, String displayName, String language, String walletPreference,
                       boolean paypalVerified, Instant createdAt, Instant lastLoginAt) {
        @Override
        public String toString() {
            return "ProfileView[memberId=" + memberId + ", personal data redacted]";
        }
    }

    record ProfilePatch(@Size(max = 16) String language, @Size(max = 16) String walletPreference,
                        @Size(max = 120) String displayName) {
        @Override
        public String toString() {
            return "ProfilePatch[personal data redacted]";
        }
    }

    record ConsentRequest(@NotBlank @Size(max = 32) String type, @Size(max = 32) String version) {
    }

    record ConsentRecord(UUID consentId, String type, String version, boolean active, Instant grantedAt, Instant revokedAt) {
    }

    private final IdentityFacade identity;

    MemberIdentityController(IdentityFacade identity) {
        this.identity = identity;
    }

    @GetMapping("/api/v1/members/me")
    ProfileView me(Actor actor) {
        UUID memberId = actor.requireMember();
        return profile(identity.member(memberId).orElseThrow(() -> new DomainException(IdentityErrors.MEMBER_NOT_FOUND,
                "No such member")));
    }

    @PatchMapping("/api/v1/members/me")
    ProfileView update(Actor actor, @Valid @RequestBody ProfilePatch body) {
        UUID memberId = actor.requireMember();
        Language language = body.language() == null ? null : Language.parse(body.language())
                .orElseThrow(() -> new DomainException(IdentityErrors.UNSUPPORTED_LANGUAGE, "Use en or es"));
        WalletPreference wallet = body.walletPreference() == null ? null : WalletPreference.parse(body.walletPreference())
                .orElseThrow(() -> new DomainException(IdentityErrors.UNKNOWN_WALLET, "Use PAYPAL or VENMO"));
        return profile(identity.updateProfile(memberId, new ProfileUpdate(language, wallet, body.displayName())));
    }

    @GetMapping("/api/v1/members/me/consents")
    List<ConsentRecord> consents(Actor actor) {
        return identity.consents(actor.requireMember()).stream().map(MemberIdentityController::record).toList();
    }

    @PostMapping("/api/v1/members/me/consents")
    ResponseEntity<ConsentRecord> grant(Actor actor, @Valid @RequestBody ConsentRequest body) {
        UUID memberId = actor.requireMember();
        GrantResult result = identity.grantConsent(memberId, new ConsentGrant(type(body.type()), body.version()));
        return ResponseEntity.status(result.recorded() ? HttpStatus.CREATED : HttpStatus.OK).body(record(result.consent()));
    }

    @DeleteMapping("/api/v1/members/me/consents/{type}")
    ResponseEntity<Void> revoke(Actor actor, @PathVariable String type) {
        UUID memberId = actor.requireMember();
        identity.revokeConsent(memberId, type(type));
        return ResponseEntity.noContent().build();
    }

    private static ConsentType type(String raw) {
        return ConsentType.parse(raw).orElseThrow(() -> new DomainException(IdentityErrors.UNKNOWN_CONSENT_TYPE,
                "Use TERMS, PRIVACY, INCOME_READ or RECORD_SHARING"));
    }

    static ProfileView profile(MemberView m) {
        return new ProfileView(m.memberId(), m.email(), m.displayName(), m.language().code(), m.walletPreference().name(),
                m.paypalVerified(), m.createdAt(), m.lastLoginAt());
    }

    private static ConsentRecord record(ConsentView c) {
        return new ConsentRecord(c.consentId(), c.type().name(), c.version(), c.active(), c.grantedAt(), c.revokedAt());
    }
}
