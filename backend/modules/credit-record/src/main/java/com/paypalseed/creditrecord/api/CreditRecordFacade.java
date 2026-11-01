package com.paypalseed.creditrecord.api;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.CreateShare;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.HistoryPage;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.IngestOutcome;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.LevelView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareCreated;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.VerifiedRecord;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What other modules may ask of the credit record. The record imports no other business module; the wiring module
 * feeds it through {@link #ingest} and lending reads {@link #levelOf}.
 */
public interface CreditRecordFacade {

    /** The member's record; a member with no facts yet gets level NEW and empty counts. */
    RecordView record(UUID memberId);

    /** What lending needs to size a loan. Level NEW (limit 0) for a member with no facts. */
    LevelView levelOf(UUID memberId);

    /** Facts newest first. Pass the previous page's {@code nextBefore} as {@code before}. */
    HistoryPage history(UUID memberId, Long before, int limit);

    /** Records one fact. Idempotent on {@code fact.factKey()}; joins the caller's transaction when there is one. */
    IngestOutcome ingest(RecordFact fact);

    /**
     * Counts the calendar months that ended without a deposit as missed, for members who had started saving.
     * Idempotent per member and month. Returns how many members were brought up to date.
     */
    int closeSavingMonths(LocalDate asOf, int limit);

    /** Makes a share link for the member's current record. Idempotent on the key; a different body is a 409. */
    ShareCreated createShare(UUID memberId, CreateShare request, String idempotencyKey);

    List<ShareView> shares(UUID memberId);

    /** Revokes the member's own share (idempotent). Another member's share, or none, is a 404. */
    void revokeShare(UUID memberId, UUID shareId);

    /** Public verification. Every failure is the same 404. */
    VerifiedRecord verify(String token);
}
