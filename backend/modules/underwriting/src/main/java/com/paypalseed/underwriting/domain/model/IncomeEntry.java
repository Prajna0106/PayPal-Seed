package com.paypalseed.underwriting.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/** Money that came into the member's account on a business date, fees and refunds already taken out. */
public record IncomeEntry(String transactionId, LocalDate date, long netCents) {
    public IncomeEntry {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(date, "date");
    }
}
