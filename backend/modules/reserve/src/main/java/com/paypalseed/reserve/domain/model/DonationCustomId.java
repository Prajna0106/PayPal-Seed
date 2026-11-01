package com.paypalseed.reserve.domain.model;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The {@code custom_id} a donation carries through PayPal: {@code reserve-donation:<intent id>}. */
public final class DonationCustomId {

    public static final String PREFIX = "reserve-donation:";
    private static final Pattern FORMAT =
            Pattern.compile("^" + PREFIX + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    private DonationCustomId() {
    }

    public static String format(UUID intentId) {
        return PREFIX + intentId;
    }

    public static Optional<UUID> parse(String customId) {
        if (customId == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(customId);
        return m.matches() ? Optional.of(UUID.fromString(m.group(1))) : Optional.empty();
    }
}
