package com.paypalseed.engines.rules.hardship;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One fired rule with the numbers that fired it. Every number a member or officer later reads is in some
 * signal's evidence, which is what the grounding checker compares against.
 *
 * @param evidence insertion-ordered; values are cents, bps, days or counts as the key names say
 */
public record Signal(String code, int points, Map<String, Long> evidence) {

    public static final String INCOME_DROP_MODERATE = "INCOME_DROP_MODERATE";
    public static final String INCOME_DROP_SEVERE = "INCOME_DROP_SEVERE";
    public static final String LOW_COVERAGE = "LOW_COVERAGE";
    public static final String CRITICAL_COVERAGE = "CRITICAL_COVERAGE";
    public static final String AUTOPAY_FAILED = "AUTOPAY_FAILED";
    public static final String AUTOPAY_FAILED_TWICE = "AUTOPAY_FAILED_TWICE";
    public static final String PAYDAY_SLIP = "PAYDAY_SLIP";
    public static final String IN_GRACE = "IN_GRACE";
    public static final String RECENT_LATE = "RECENT_LATE";
    public static final String SAVINGS_STRESS = "SAVINGS_STRESS";
    public static final String MEMBER_REQUEST = "MEMBER_REQUEST";
    public static final String AI_ESCALATION = "AI_ESCALATION";

    public Signal {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        HardshipPolicy.require(points >= 0, "points must not be negative");
        Objects.requireNonNull(evidence, "evidence");
        evidence.forEach((k, v) -> HardshipPolicy.require(k != null && v != null, "evidence keys and values are required"));
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }

    /** Builds a signal from alternating key and value arguments: {@code of(code, 5, "dropBps", 5200L)}. */
    static Signal of(String code, int points, Object... keyValues) {
        HardshipPolicy.require(keyValues.length % 2 == 0, "evidence needs key and value pairs");
        Map<String, Long> evidence = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            evidence.put((String) keyValues[i], ((Number) keyValues[i + 1]).longValue());
        }
        return new Signal(code, points, evidence);
    }
}
