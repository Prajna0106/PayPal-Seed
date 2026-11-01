package com.paypalseed.creditrecord.application.usecases;

import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.application.ports.RecordStore;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A month with no deposit is a fact that only the calendar can produce. This turns each finished month into a fact
 * for members who had started saving, so "missed" does not wait for the next deposit to be noticed.
 */
@Service
public class SavingsMonthClose {

    private final RecordStore store;
    private final RecordIngest ingest;

    public SavingsMonthClose(RecordStore store, RecordIngest ingest) {
        this.store = store;
        this.ingest = ingest;
    }

    /** Not one transaction: each member commits alone, so one failure does not hold back the others. */
    public int closeThrough(LocalDate asOf, int limit) {
        LocalDate throughMonth = YearMonth.from(asOf).minusMonths(1).atDay(1);
        List<UUID> due = store.membersToClose(throughMonth, limit);
        due.forEach(member -> ingest.ingest(RecordFact.savingMonthsClosed(member, asOf)));
        return due.size();
    }
}
