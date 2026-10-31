package com.paypalseed.engines.rules.hardship;

import java.util.Objects;
import java.util.Optional;

/**
 * Frequency caps of 03 §7. F4's primitives already enforce date and amount limits; these stop relief from
 * becoming a permanent plan. Only cases that reached APPLIED count.
 */
public final class HardshipCaps {

    /** Applied options on this loan; "last 183 days" is {@code capWindowDays} in policy v1. */
    public record History(int appliedALast183Days, int appliedBLast183Days, int appliedBTotal, int appliedCTotal) {

        public static final History NONE = new History(0, 0, 0, 0);

        public History {
            HardshipPolicy.require(appliedALast183Days >= 0 && appliedBLast183Days >= 0 && appliedBTotal >= appliedBLast183Days
                    && appliedCTotal >= 0, "history counts out of range");
        }
    }

    private HardshipCaps() {
    }

    /** @return the cap that would be broken, worded for the error detail; empty when allowed */
    public static Optional<String> violation(OptionKind kind, History history, HardshipPolicy policy) {
        Objects.requireNonNull(history, "history");
        HardshipPolicy.Options o = policy.options();
        return switch (Objects.requireNonNull(kind, "kind")) {
            case A_MOVE_DATE -> history.appliedALast183Days() >= o.moveDateCapPerWindow()
                    ? Optional.of("move date: " + o.moveDateCapPerWindow() + " per " + o.capWindowDays() + " days (used "
                            + history.appliedALast183Days() + ")")
                    : Optional.empty();
            case B_PAUSE -> history.appliedBLast183Days() >= o.pauseCapPerWindow() || history.appliedBTotal() >= o.pauseCapPerLoan()
                    ? Optional.of("pause: " + o.pauseCapPerWindow() + " per " + o.capWindowDays() + " days and "
                            + o.pauseCapPerLoan() + " per loan (used " + history.appliedBLast183Days() + " and "
                            + history.appliedBTotal() + ")")
                    : Optional.empty();
            case C_RESTRUCTURE -> history.appliedCTotal() >= o.restructureCapPerLoan()
                    ? Optional.of("restructure: " + o.restructureCapPerLoan() + " per loan (used " + history.appliedCTotal() + ")")
                    : Optional.empty();
            case D_PAYMENT -> Optional.empty();
        };
    }
}
