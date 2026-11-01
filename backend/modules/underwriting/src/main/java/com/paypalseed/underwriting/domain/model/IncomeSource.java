package com.paypalseed.underwriting.domain.model;

import java.util.Locale;

/** Where a member's weekly income comes from (ADR 0018). */
public enum IncomeSource {
    /** Weeks put there by the internal seeding endpoint: demos and sandboxes. Nothing is fetched. */
    SEEDED,
    /** Weeks read from PayPal Transaction Search for members with an active income consent. */
    TRANSACTION_SEARCH;

    /** Accepts {@code transaction-search} as well as {@code TRANSACTION_SEARCH}; anything unknown fails at start-up. */
    public static IncomeSource parse(String raw) {
        String normal = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (normal.isEmpty()) {
            return SEEDED;
        }
        try {
            return valueOf(normal);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "seed.underwriting.income.source must be SEEDED or TRANSACTION_SEARCH, not '" + raw + "'", e);
        }
    }
}
