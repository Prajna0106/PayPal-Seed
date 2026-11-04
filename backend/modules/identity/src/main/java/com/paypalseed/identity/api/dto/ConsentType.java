package com.paypalseed.identity.api.dto;

import java.util.Locale;
import java.util.Optional;

/**
 * What a member can agree to. {@code INCOME_READ} and {@code FAIR_CHECK} are the member-facing records of the one
 * consent that underwriting enforces for the fair loan check (ADR 0012, ADR 0026): identity records, underwriting
 * enforces, and withdrawing either line withdraws the consent.
 */
public enum ConsentType {
    TERMS,
    PRIVACY,
    INCOME_READ,
    RECORD_SHARING,
    FAIR_CHECK;

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
