package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime switches of the safety net. The policy itself is versioned code ({@link HardshipPolicy#v1()}), not
 * configuration, so a decision always names the exact rules it was made under.
 */
@Component
public class HardshipSettings {

    private final HardshipPolicy policy = HardshipPolicy.v1();
    private final boolean checkerRequired;
    private final Duration agentDeadline;

    public HardshipSettings(@Value("${seed.hardship.checker-required:true}") boolean checkerRequired,
                            @Value("${seed.hardship.agent-deadline:PT90S}") Duration agentDeadline) {
        this.checkerRequired = checkerRequired;
        this.agentDeadline = agentDeadline;
    }

    public HardshipPolicy policy() {
        return policy;
    }

    /** Tiers B and C need a second officer unless this is switched off (cut-line item 7). */
    public boolean checkerRequired() {
        return checkerRequired;
    }

    /** How long the outreach waits for the Guardian before the templates go out instead. */
    public Duration agentDeadline() {
        return agentDeadline;
    }
}
