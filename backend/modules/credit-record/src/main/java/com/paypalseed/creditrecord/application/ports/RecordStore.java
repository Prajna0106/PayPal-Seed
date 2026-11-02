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

    /** Newest first; {@code before} is exclusive and null for the first page. */
    List<StoredFact> history(UUID memberId, Long before, int limit);

    /** Members who started saving and whose last counted month is before {@code throughMonth} (a first of month). */
    List<UUID> membersToClose(LocalDate throughMonth, int limit);
}
