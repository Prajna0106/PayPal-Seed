package com.paypalseed.fraud.domain.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** How signals add up to a score. Plain arithmetic an officer can redo by hand. */
public final class FlagScore {

    private FlagScore() {
    }

    /**
     * The sum, over rules, of the heaviest signal each rule raised. A rule that keeps firing as evidence grows counts
     * once, so a fifth goal does not make the member look five times worse than a fourth.
     */
    public static int of(List<Signal> signals) {
        Map<String, Integer> heaviest = new HashMap<>();
        for (Signal s : signals) {
            heaviest.merge(s.code(), s.weight(), Math::max);
        }
        return heaviest.values().stream().mapToInt(Integer::intValue).sum();
    }
}
