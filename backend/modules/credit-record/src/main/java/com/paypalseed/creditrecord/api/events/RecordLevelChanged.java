package com.paypalseed.creditrecord.api.events;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A member's record level changed. Published in the transaction that recorded the fact; consumers dedupe on
 * {@code eventId}. {@code cause} is {@code PROMOTION} or {@code DEFAULT}: those are the only two ways a level moves.
 */
public record RecordLevelChanged(UUID eventId, UUID memberId, String fromLevel, String toLevel, int toLevelRank,
                                 long loanLimitCents, String cause, LocalDate businessDate) {
}
