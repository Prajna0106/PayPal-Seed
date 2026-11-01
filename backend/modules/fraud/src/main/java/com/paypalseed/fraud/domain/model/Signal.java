package com.paypalseed.fraud.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A rule that fired. Everything an officer needs to explain it is here: a stable code, how much it counts, a plain
 * sentence, the numbers behind it and the ids of the facts it used. {@code evidence} holds counts, windows and times
 * only; it never holds a name, an email or a raw identifier.
 */
public record Signal(String code, int weight, String explanation, Map<String, Object> evidence, List<Long> factIds,
                     Instant detectedAt) {

    public Signal {
        if (weight <= 0) {
            throw new IllegalArgumentException("a signal must weigh something");
        }
        evidence = Map.copyOf(evidence);
        factIds = List.copyOf(factIds);
    }

    /**
     * Identifies the finding: the same rule on the same facts and the same numbers gives the same fingerprint, so
     * re-evaluating changes nothing, and growing evidence (a fifth goal in the window) is a new finding.
     */
    public String fingerprint() {
        StringBuilder text = new StringBuilder(code).append('|').append(factIds.stream().sorted().toList());
        new TreeMap<>(evidence).forEach((k, v) -> text.append('|').append(k).append('=').append(v));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
