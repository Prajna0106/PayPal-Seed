package com.paypalseed.identity.api.dto;

import java.util.Locale;
import java.util.Optional;

/** Which wallet the member saves from and is paid into. */
public enum WalletPreference {
    PAYPAL,
    VENMO;

    public static Optional<WalletPreference> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normal = raw.trim().toUpperCase(Locale.ROOT);
        for (WalletPreference w : values()) {
            if (w.name().equals(normal)) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }
}
