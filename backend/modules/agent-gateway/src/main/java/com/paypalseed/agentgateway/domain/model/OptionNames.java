package com.paypalseed.agentgateway.domain.model;

import java.util.Map;
import java.util.Optional;

/**
 * Maps hardship's option labels and approval tiers to the names in {@code agent-tools.yaml}. Hardship
 * calls the options A to D; agents and members see what each one does.
 */
public final class OptionNames {

    private static final Map<String, String> BY_LABEL = Map.of(
            "A", "MOVE_DATE",
            "B", "PAUSE_ONE_MONTH",
            "C", "EXTEND_PLAN",
            "D", "PAY_WHAT_YOU_CAN");

    private static final Map<String, String> TIERS = Map.of(
            "AUTO_POLICY", "MEMBER_ONLY",
            "MAKER_CHECKER", "OFFICER_AND_CHECKER",
            "NONE", "NONE");

    private OptionNames() {
    }

    public static String forLabel(String label) {
        String name = BY_LABEL.get(label);
        if (name == null) {
            throw new IllegalArgumentException("unknown option label " + label);
        }
        return name;
    }

    public static Optional<String> labelFor(String optionName) {
        return BY_LABEL.entrySet().stream().filter(e -> e.getValue().equals(optionName)).map(Map.Entry::getKey).findFirst();
    }

    public static String tier(String hardshipTier) {
        return TIERS.getOrDefault(hardshipTier, hardshipTier);
    }

    /** The contract's short form: {@code sha256:} plus the first 16 hex digits of the snapshot hash. */
    public static String previewHash(String snapshotHash) {
        if (snapshotHash == null) {
            return null;
        }
        return "sha256:" + (snapshotHash.length() > 16 ? snapshotHash.substring(0, 16) : snapshotHash);
    }
}
