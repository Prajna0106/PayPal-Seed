package com.paypalseed.creditrecord.api.events;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A member's record level changed. Published in the transaction that recorded the fact; consumers dedupe on
 * {@code eventId}. {@code cause} is {@code PROMOTION} or {@code DEFAULT}, or {@code CORRECTION}: a level that had risen on a loan
 * the safety fund paid, or on a loan that was voided, goes back to what the member's own facts support (ADR 0025).
 */
public record RecordLevelChanged(UUID eventId, UUID memberId, String fromLevel, String toLevel, int toLevelRank,
                                 long loanLimitCents, String cause, LocalDate businessDate) {
}
