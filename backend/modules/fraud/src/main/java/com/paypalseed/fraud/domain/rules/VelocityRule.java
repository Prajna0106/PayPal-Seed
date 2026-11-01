package com.paypalseed.fraud.domain.rules;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Fact;
import com.paypalseed.fraud.domain.model.Signal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Too many of one kind of thing in a short window: new goals, loan applications, failed payments. Finds the busiest
 * window of the given length; the rule fires when it holds at least {@code threshold} facts.
 */
public final class VelocityRule implements Rule {

    private final String code;
    private final FactKind kind;
    private final Duration window;
    private final int threshold;
    private final int weight;
    private final String what;

    public VelocityRule(String code, FactKind kind, Duration window, int threshold, int weight, String what) {
        if (threshold < 2 || weight <= 0 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("a velocity rule needs a threshold of 2 or more, a weight and a window");
        }
        this.code = code;
        this.kind = kind;
        this.window = window;
        this.threshold = threshold;
        this.weight = weight;
        this.what = what;
    }

    /** Four new goals in a day is unusual for one person; two or three are normal (one per child, one per bill). */
    public static VelocityRule goals() {
        return new VelocityRule("GOAL_VELOCITY", FactKind.GOAL_CREATED, Duration.ofHours(24), 4, 20, "new savings goals");
    }

    public static VelocityRule applications() {
        return new VelocityRule("APPLICATION_VELOCITY", FactKind.APPLICATION_SUBMITTED, Duration.ofDays(7), 2, 30,
                "loan applications");
    }

    /**
     * Weighs little on purpose: a member who cannot pay is often a member in hardship, who deserves help from the safety
     * net and not suspicion. It only adds to other signals.
     */
    public static VelocityRule failedPayments() {
        return new VelocityRule("REPEATED_FAILED_PAYMENTS", FactKind.PAYMENT_FAILED, Duration.ofDays(30), 3, 10,
                "failed payments");
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Optional<Signal> evaluate(RuleContext context) {
        List<Fact> facts = context.of(kind);
        int bestStart = 0;
        int bestEnd = -1;
        int start = 0;
        for (int end = 0; end < facts.size(); end++) {
            while (Duration.between(facts.get(start).occurredAt(), facts.get(end).occurredAt()).compareTo(window) > 0) {
                start++;
            }
            if (end - start > bestEnd - bestStart) {
                bestStart = start;
                bestEnd = end;
            }
        }
        int count = bestEnd - bestStart + 1;
        if (count < threshold) {
            return Optional.empty();
        }
        List<Fact> inWindow = facts.subList(bestStart, bestEnd + 1);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("count", count);
        evidence.put("threshold", threshold);
        evidence.put("windowHours", window.toHours());
        evidence.put("firstAt", inWindow.get(0).occurredAt().toString());
        evidence.put("lastAt", inWindow.get(count - 1).occurredAt().toString());
        return Optional.of(new Signal(code, weight,
                count + " " + what + " within " + describe(window) + " (the usual limit is " + (threshold - 1) + ")", evidence,
                inWindow.stream().map(Fact::id).toList(), context.asOf()));
    }

    private static String describe(Duration window) {
        return window.toHours() % 24 == 0 && window.toHours() >= 48 ? window.toDays() + " days" : window.toHours() + " hours";
    }
}
