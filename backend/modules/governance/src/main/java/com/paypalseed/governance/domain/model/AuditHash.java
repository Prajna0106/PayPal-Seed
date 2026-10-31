package com.paypalseed.governance.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** {@code hash = SHA-256(prevHash || canonicalJson)}. The first entry links to 32 zero bytes. */
public final class AuditHash {

    public static final int LENGTH = 32;

    private AuditHash() {
    }

    public static byte[] genesis() {
        return new byte[LENGTH];
    }

    public static byte[] next(byte[] prevHash, String canonicalEntryJson) {
        if (prevHash.length != LENGTH) {
            throw new IllegalArgumentException("prevHash must be " + LENGTH + " bytes");
        }
        MessageDigest sha = sha256();
        sha.update(prevHash);
        sha.update(canonicalEntryJson.getBytes(StandardCharsets.UTF_8));
        return sha.digest();
    }

    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
