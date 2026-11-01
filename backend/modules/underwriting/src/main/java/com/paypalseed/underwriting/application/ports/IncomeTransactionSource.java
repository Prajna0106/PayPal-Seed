package com.paypalseed.underwriting.application.ports;

import com.paypalseed.underwriting.domain.model.IncomeEntry;
import java.time.LocalDate;
import java.util.List;

/** Reads a member's income credits from outside. Read-only, bounded in time, and failing as a whole or not at all. */
public interface IncomeTransactionSource {

    /**
     * Credits dated in {@code [from, to]}, fees and refunds netted. Throws a retryable
     * {@code income-source-unavailable} problem when the source fails or times out; it never returns part of a range.
     */
    List<IncomeEntry> credits(String accountReference, LocalDate from, LocalDate to);
}
