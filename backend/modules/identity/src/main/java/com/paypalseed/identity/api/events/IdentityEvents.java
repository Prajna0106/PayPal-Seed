package com.paypalseed.identity.api.events;

import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.Language;
import com.paypalseed.identity.api.dto.WalletPreference;
import java.time.Instant;
import java.util.UUID;

/** Facts identity publishes. None carries an email, a name or a payer id, so listeners never handle personal data. */
public final class IdentityEvents {

    private IdentityEvents() {
    }

    public sealed interface IdentityEvent permits MemberRegistered, MemberProfileChanged, ConsentGranted, ConsentRevoked {
        UUID eventId();

        UUID memberId();

        Instant occurredAt();
    }

    public record MemberRegistered(UUID eventId, UUID memberId, Language language, Instant occurredAt)
            implements IdentityEvent {
    }

    public record MemberProfileChanged(UUID eventId, UUID memberId, Language language, WalletPreference walletPreference,
                                       Instant occurredAt) implements IdentityEvent {
    }

    public record ConsentGranted(UUID eventId, UUID memberId, UUID consentId, ConsentType type, String version,
                                 Instant occurredAt) implements IdentityEvent {
    }

    /** Listeners that enforce a consent elsewhere (for example income read in underwriting) stop using it on this event. */
    public record ConsentRevoked(UUID eventId, UUID memberId, UUID consentId, ConsentType type, String version,
                                 Instant occurredAt) implements IdentityEvent {
    }
}
