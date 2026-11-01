package com.paypalseed.identity.api.dto;

import java.util.Locale;
import java.util.Optional;

/** Languages the product speaks. */
public enum Language {
    EN("en"),
    ES("es");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Exact code ({@code en}, {@code es}), any case. */
    public static Optional<Language> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normal = raw.trim().toLowerCase(Locale.ROOT);
        for (Language l : values()) {
            if (l.code.equals(normal)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    /** A browser or PayPal locale such as {@code es-MX} or {@code en_US}; only the language part counts. */
    public static Optional<Language> fromLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return Optional.empty();
        }
        String trimmed = locale.trim();
        int cut = 0;
        while (cut < trimmed.length() && trimmed.charAt(cut) != '-' && trimmed.charAt(cut) != '_') {
            cut++;
        }
        return parse(trimmed.substring(0, cut));
    }
}
