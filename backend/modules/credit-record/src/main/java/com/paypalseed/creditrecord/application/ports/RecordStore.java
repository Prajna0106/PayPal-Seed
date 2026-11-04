package com.paypalseed.creditrecord.application.ports;

import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.domain.model.RecordState;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of the fact store and the per-member state. */
public interface RecordStore {

    /** A fact as stored, for the history page. */
    record StoredFact(long seq, RecordFact.Kind kind, LocalDate occurredOn, Integer daysLate) {
    }

    /** Serialises writers for one member until the transaction ends. */
    void lock(UUID memberId);

    /** False when the fact key was already recorded; nothing is written then. */
    boolean append(RecordFact fact);

    Optional<RecordState> find(UUID memberId);

    void save(RecordState state);

    /** Every fact of the member, oldest first (the order they were folded in), for the rebuild after a loan is voided. */
    List<RecordFact> facts(UUID memberId);

    /**
     * Newest first; {@code before} is exclusive and null for the first page. Facts of a voided loan, and the voiding
     * itself, are not part of the member's history.
     */
    List<StoredFact> history(UUID memberId, Long before, int limit);

    /** Members who started saving and whose last counted month is before {@code throughMonth} (a first of month). */
    List<UUID> membersToClose(LocalDate throughMonth, int limit);
}
