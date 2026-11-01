package com.paypalseed.engines.rules.hardship;

import java.util.Objects;

/** Who must agree besides the member: nobody (policy), or a maker and a checker. */
public enum ApprovalTier {
    AUTO_POLICY,
    MAKER_CHECKER,
    NONE;

    public static ApprovalTier forKind(OptionKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case A_MOVE_DATE -> AUTO_POLICY;
            case B_PAUSE, C_RESTRUCTURE -> MAKER_CHECKER;
            case D_PAYMENT -> NONE;
        };
    }
}
