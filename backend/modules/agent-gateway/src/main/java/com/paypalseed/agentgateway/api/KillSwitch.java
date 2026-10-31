package com.paypalseed.agentgateway.api;

import java.time.Instant;
import java.util.Map;

/**
 * Agent operating mode (04 §10). The most restrictive of the {@code GLOBAL} row and the agent's own row
 * applies. Automatic trips only ever move to {@code TEMPLATES_ONLY}; only a person moves a mode back up.
 */
public interface KillSwitch {

    String GLOBAL = "GLOBAL";

    /** Ordered from least to most restrictive. */
    enum Mode {
        /** Agent output is shown. */
        LIVE,
        /** The agent runs and its output is stored, but members and officers see templates. */
        SHADOW,
        /** No model calls at all. */
        TEMPLATES_ONLY;

        public boolean moreRestrictiveThan(Mode other) {
            return ordinal() > other.ordinal();
        }
    }

    enum TrippedBy {
        OFFICER,
        AUTO_VALIDATOR_REJECTIONS,
        AUTO_GROUNDING_FAILURES,
        AUTO_BUDGET,
        AUTO_INJECTION_CANARY
    }

    /** The effective state for an agent; {@code circuits} maps each model to CLOSED, HALF_OPEN or OPEN. */
    record State(Mode mode, Instant since, String reason, TrippedBy trippedBy, Map<String, String> circuits) {
    }

    Mode mode(String agent);

    State state(String agent);

    /** A person sets the mode of {@code scope} ({@code GLOBAL} or an agent name). Recorded in the history. */
    void set(String scope, Mode mode, String reason, String changedBy);

    /** Feeds the rejection-rate trip: more than 20% of the last 50 validator verdicts. */
    void recordValidatorOutcome(String agent, boolean rejected);

    /** Feeds the grounding trip: more than 3 failures within one hour. */
    void recordGroundingFailure(String agent);
}
