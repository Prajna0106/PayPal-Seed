package com.paypalseed.engines.rules.hardship;

import java.time.LocalDate;
import java.util.Objects;

/** One instalment that still has money owed, before or after an option. */
public record OwedRow(LocalDate dueDate, long remainingCents) {

    public OwedRow {
        Objects.requireNonNull(dueDate, "dueDate");
        HardshipPolicy.require(remainingCents > 0, "an owed row must have money remaining");
    }
}
