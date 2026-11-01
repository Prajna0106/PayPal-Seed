package com.paypalseed.fraud.application.usecases;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runtime knobs of the fraud rules. The rules themselves are versioned code ({@code RuleSet}), not configuration. */
@Component
public class FraudSettings {

    private final int flagThreshold;
    private final Duration lookback;

    public FraudSettings(@Value("${seed.fraud.flag-threshold:40}") int flagThreshold,
                         @Value("${seed.fraud.lookback:P90D}") Duration lookback) {
        if (flagThreshold <= 0) {
            throw new IllegalArgumentException("seed.fraud.flag-threshold must be positive");
        }
        if (lookback.isZero() || lookback.isNegative()) {
            throw new IllegalArgumentException("seed.fraud.lookback must be positive");
        }
        this.flagThreshold = flagThreshold;
        this.lookback = lookback;
    }

    /** A flag is raised when the score reaches this. 40 is one strong signal, or several weak ones together. */
    public int flagThreshold() {
        return flagThreshold;
    }

    /** How far back the rules read. */
    public Duration lookback() {
        return lookback;
    }
}
