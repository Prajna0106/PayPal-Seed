package com.paypalseed.deskquery.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The opaque member reference every desk list carries; it identifies a member to the desk and reveals nothing about them. */
public final class MemberRefs {

    private MemberRefs() {
    }

    /**
     * Savings identifies members by a text id that is a UUID in practice. Anything else maps to the same name-based UUID the
     * savings module uses for its ledger accounts, so a member has one reference on every list.
     */
    public static UUID ofSavings(String savingsMemberId) {
        try {
            return UUID.fromString(savingsMemberId);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(savingsMemberId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
