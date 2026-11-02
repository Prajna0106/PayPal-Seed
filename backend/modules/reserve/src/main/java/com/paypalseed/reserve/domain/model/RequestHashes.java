package com.paypalseed.reserve.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fingerprints of a request body, so a reused idempotency key with another body is told apart from a replay. */
public final class RequestHashes {

    private RequestHashes() {
    }

    /** SHA-256 of the parts joined with a separator that cannot appear inside a part. */
    public static String of(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            joined.append(part == null ? "" : part).append('\u001f');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
