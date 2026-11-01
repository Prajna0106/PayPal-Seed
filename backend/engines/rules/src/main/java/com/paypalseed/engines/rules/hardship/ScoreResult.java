package com.paypalseed.engines.rules.hardship;

import java.util.List;
import java.util.Objects;

public record ScoreResult(int points, RiskLevel level, List<Signal> signals) {

    public ScoreResult {
        HardshipPolicy.require(points >= 0, "points must not be negative");
        Objects.requireNonNull(level, "level");
        signals = List.copyOf(signals);
    }

    public boolean fired(String code) {
        return signals.stream().anyMatch(s -> s.code().equals(code));
    }
}
