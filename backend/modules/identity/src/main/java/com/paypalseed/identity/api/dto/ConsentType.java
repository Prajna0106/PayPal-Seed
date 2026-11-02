package com.paypalseed.identity.api.dto;

import java.util.Locale;
import java.util.Optional;

/**
 * What a member can agree to. {@code INCOME_READ} is the member-facing record of the consent that
 * underwriting enforces for the fair loan check (ADR 0012): identity records, underwriting enforces.
 */
public enum ConsentType {
    TERMS,
    PRIVACY,
    INCOME_READ,
    RECORD_SHARING;

    public static Optional<ConsentType> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normal = raw.trim().toUpperCase(Locale.ROOT);
        for (ConsentType t : values()) {
            if (t.name().equals(normal)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
