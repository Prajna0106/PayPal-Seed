package com.paypalseed.identity.domain.model;

import com.paypalseed.identity.api.dto.Language;
import com.paypalseed.identity.api.dto.WalletPreference;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A person who signed in with PayPal. Changes return a new value, so the rules (what a login may overwrite, what a
 * profile edit may change) can be tested without a database.
 */
public record Member(UUID id, String paypalPayerId, String email, String displayName, Language language,
                     WalletPreference walletPreference, boolean paypalVerified, Instant createdAt, Instant lastLoginAt,
                     Instant updatedAt) {

    public static final int MAX_NAME = 120;
    public static final int MAX_EMAIL = 254;

    public static Member register(UUID id, String paypalPayerId, String email, String name, Boolean verified,
                                  Language language, Instant now) {
        return new Member(id, requirePayerId(paypalPayerId), clean(email, MAX_EMAIL), clean(name, MAX_NAME),
                language == null ? Language.EN : language, WalletPreference.PAYPAL, Boolean.TRUE.equals(verified), now, now,
                now);
    }

    /**
     * A later login. A field PayPal did not send keeps its stored value; a language is replaced only when one was
     * provided, so a member who chose Spanish is never put back to English by a login without a locale.
     */
    public Member afterLogin(String newEmail, String newName, Boolean verified, Language newLanguage, Instant now) {
        return new Member(id, paypalPayerId, orStored(clean(newEmail, MAX_EMAIL), email),
                orStored(clean(newName, MAX_NAME), displayName), newLanguage == null ? language : newLanguage,
                walletPreference, verified == null ? paypalVerified : verified, createdAt, now, now);
    }

    /** The member edits their own profile. Null means unchanged; a display name cannot be emptied. */
    public Member withProfile(Language newLanguage, WalletPreference newWallet, String newName, Instant now) {
        String name = displayName;
        if (newName != null) {
            name = clean(newName, MAX_NAME);
            if (name == null) {
                throw new DomainException(IdentityErrors.INVALID_NAME, "The display name must not be empty");
            }
        }
        Language nextLanguage = newLanguage == null ? language : newLanguage;
        WalletPreference nextWallet = newWallet == null ? walletPreference : newWallet;
        if (nextLanguage == language && nextWallet == walletPreference && Objects.equals(name, displayName)) {
            return this;
        }
        return new Member(id, paypalPayerId, email, name, nextLanguage, nextWallet, paypalVerified, createdAt, lastLoginAt, now);
    }

    public boolean sameProfileChoices(Member other) {
        return language == other.language && walletPreference == other.walletPreference;
    }

    public static String requirePayerId(String payerId) {
        String trimmed = payerId == null ? "" : payerId.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new DomainException(IdentityErrors.INVALID_PAYER_ID, "A PayPal payer id of 1 to 64 characters is required");
        }
        return trimmed;
    }

    private static String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String orStored(String provided, String stored) {
        return provided != null ? provided : stored;
    }

    /** Never prints personal data. */
    @Override
    public String toString() {
        return "Member[id=" + id + ", personal data redacted]";
    }
}
