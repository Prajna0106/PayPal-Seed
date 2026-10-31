package com.paypalseed.engines.rules.hardship;

import java.time.LocalDate;
import java.util.Objects;

/** One proposed instalment as previewed: part of what the member saw and the officers approved. */
public record SnapshotRow(int seq, LocalDate dueDate, long amountCents, long remainingCents) {

    public SnapshotRow {
        HardshipPolicy.require(seq >= 1, "seq must be positive");
        Objects.requireNonNull(dueDate, "dueDate");
        HardshipPolicy.require(amountCents > 0 && remainingCents >= 0 && remainingCents <= amountCents,
                "amounts out of range");
    }
}
