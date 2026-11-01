package com.paypalseed.engines.rules.hardship;

/** The four relief options, in order of how much they change the plan. */
public enum OptionKind {
    A_MOVE_DATE("A", "MOVE_DATE"),
    B_PAUSE("B", "DEFER_TO_END"),
    C_RESTRUCTURE("C", "REAMORTIZE"),
    D_PAYMENT("D", "PAYMENT");

    private final String label;
    private final String primitive;

    OptionKind(String label, String primitive) {
        this.label = label;
        this.primitive = primitive;
    }

    public String label() {
        return label;
    }

    /** F4's {@code ReschedulePrimitive} name, or {@code PAYMENT} for option D (not a reschedule). */
    public String primitive() {
        return primitive;
    }

    public static OptionKind fromLabel(String label) {
        for (OptionKind k : values()) {
            if (k.label.equals(label)) {
                return k;
            }
        }
        throw new IllegalArgumentException("unknown option label: " + label);
    }
}
