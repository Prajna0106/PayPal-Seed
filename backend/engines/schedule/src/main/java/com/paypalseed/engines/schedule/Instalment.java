package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.util.Objects;

public record Instalment(int seq, LocalDate dueDate, long amountCents) {

    public Instalment {
        Objects.requireNonNull(dueDate, "dueDate");
        if (seq < 1) {
            throw new IllegalArgumentException("seq must start at 1");
        }
        if (amountCents <= 0 || amountCents % 100 != 0) {
            throw new IllegalArgumentException("instalment must be a positive whole-dollar amount: " + amountCents);
        }
    }

    /** Stable reference used by timers and dedupe keys, for example {@code v2:s3}. */
    public String ref(int version) {
        return "v" + version + ":s" + seq;
    }
}
