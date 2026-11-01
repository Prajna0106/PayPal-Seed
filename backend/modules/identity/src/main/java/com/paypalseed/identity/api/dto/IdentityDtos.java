package com.paypalseed.identity.api.dto;

import com.paypalseed.platform.security.Actor;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Commands and views of the identity facade. Email and display name are personal data, so the records that
 * hold them print a masked {@code toString}: an accidental log line never leaks them.
 */
public final class IdentityDtos {

    private IdentityDtos() {
    }

    /** What the web server learned from Log in with PayPal. Absent fields never erase stored values. */
    public record PayPalLogin(String paypalPayerId, String email, String name, Boolean verified, String locale) {
        @Override
        public String toString() {
            return "PayPalLogin[personal data redacted]";
        }
    }

    public record MemberView(UUID memberId, String email, String displayName, Language language,
                             WalletPreference walletPreference, boolean paypalVerified, Instant createdAt,
                             Instant lastLoginAt) {
        @Override
        public String toString() {
            return "MemberView[memberId=" + memberId + ", personal data redacted]";
        }
    }

    /**
     * Where a payout goes: the PayPal payer id and email from the member's login record, nothing else. The email may be
     * null when PayPal did not share one. Personal data: never log it.
     */
    public record PayoutRecipient(String payerId, String email) {
        @Override
        public String toString() {
            return "PayoutRecipient[personal data redacted]";
        }
    }

    public record LoginResult(boolean created, MemberView member) {
    }

    /** Only the fields that are not null change. */
    public record ProfileUpdate(Language language, WalletPreference walletPreference, String displayName) {
        @Override
        public String toString() {
            return "ProfileUpdate[language=" + language + ", walletPreference=" + walletPreference + ", displayName redacted]";
        }
    }

    /** {@code version} null means the current version of the text. */
    public record ConsentGrant(ConsentType type, String version) {
    }

    public record ConsentView(UUID consentId, ConsentType type, String version, boolean active, Instant grantedAt,
                              Instant revokedAt) {
    }

    /** {@code recorded} is false when the same consent was already active, so a replay changes nothing. */
    public record GrantResult(boolean recorded, ConsentView consent) {
    }

    /** Roles must be some of OFFICER, CHECKER and ADMIN. {@code active} null means true. */
    public record StaffUpsert(String displayName, String email, Set<Actor.Role> roles, Boolean active) {
        @Override
        public String toString() {
            return "StaffUpsert[roles=" + roles + ", active=" + active + ", personal data redacted]";
        }
    }

    public record StaffView(UUID staffId, String displayName, String email, List<Actor.Role> roles, boolean active) {
        @Override
        public String toString() {
            return "StaffView[staffId=" + staffId + ", roles=" + roles + ", active=" + active + ", personal data redacted]";
        }
    }
}
