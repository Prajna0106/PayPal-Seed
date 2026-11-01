package com.paypalseed.lending.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The pseudonymous staff reference that events and read models carry instead of a staff id. It has the same form
 * governance gives the same person ({@code staff:} and 16 hex digits of a salted SHA-256), so the desk shows one
 * reference per officer everywhere. Governance owns the rule; this copy exists because lending may not import
 * governance, and a test pins the value.
 */
public final class StaffReferences {

    private StaffReferences() {
    }

    public static String of(UUID staffId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("governance-staff:" + staffId).getBytes(StandardCharsets.UTF_8));
            return "staff:" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
