package com.paypalseed.engines.rules.hardship;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One hash for exactly what the member saw, what the officers approved and what is applied (HINV-14,
 * 03 §8). It covers the proposed rows, not the date: tomorrow's identical preview has the same hash, while
 * a payment that changes what is owed changes it.
 */
public final class SnapshotHash {

    private SnapshotHash() {
    }

    /** Lowercase hex SHA-256 of canonical JSON (keys sorted, no whitespace). */
    public static String of(UUID loanId, int baseVersion, String primitive, Map<String, Integer> args,
                            List<SnapshotRow> proposed) {
        Objects.requireNonNull(loanId, "loanId");
        HardshipPolicy.require(baseVersion >= 1, "baseVersion must be positive");
        Objects.requireNonNull(primitive, "primitive");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(proposed, "proposed");

        StringBuilder json = new StringBuilder(64 + proposed.size() * 80);
        json.append("{\"args\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : new TreeMap<>(args).entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            string(json, e.getKey()).append(':').append(Objects.requireNonNull(e.getValue(), "arg value"));
        }
        json.append("},\"baseVersion\":").append(baseVersion).append(",\"loanId\":");
        string(json, loanId.toString()).append(",\"primitive\":");
        string(json, primitive).append(",\"proposed\":[");
        for (int i = 0; i < proposed.size(); i++) {
            SnapshotRow r = proposed.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"amountCents\":").append(r.amountCents()).append(",\"dueDate\":");
            string(json, r.dueDate().toString()).append(",\"remainingCents\":").append(r.remainingCents())
                    .append(",\"seq\":").append(r.seq()).append('}');
        }
        json.append("]}");
        return sha256(json.toString());
    }

    private static StringBuilder string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"');
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
