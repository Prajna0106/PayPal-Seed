package com.paypalseed.engines.rules.hardship;

import java.util.Map;
import java.util.Objects;

/**
 * An option that previewed successfully, with its metrics.
 *
 * @param args     primitive arguments ({@code seq}, {@code days}, {@code extraMonths}, {@code amountCents}); option D
 *                 also carries {@code coversInstalment} = 1 when its amount pays the whole target instalment
 * @param policyOk whether the frequency caps allow it
 */
public record OptionCandidate(OptionKind kind, Map<String, Integer> args, OptionMetrics metrics, boolean policyOk) {

    public static final String COVERS_INSTALMENT = "coversInstalment";
    public static final String EXTRA_MONTHS = "extraMonths";

    public OptionCandidate {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(metrics, "metrics");
        args = Map.copyOf(args);
    }

    public int arg(String name) {
        return args.getOrDefault(name, 0);
    }

    public ApprovalTier tier() {
        return ApprovalTier.forKind(kind);
    }
}
