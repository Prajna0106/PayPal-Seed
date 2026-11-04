package com.paypalseed.creditrecord.application.ports;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareDetail;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of frozen snapshots and the share links that point at them. */
public interface ShareStore {

    /** What a share shows, frozen at creation. Holds no personal data. */
    record Snapshot(UUID id, UUID memberId, int levelRank, LocalDate recordSince, int loansOnPlan, String onTimeBand,
                    int defaultsAtCapture, LocalDate capturedOn, int loansWithFundHelp) {
        /** A snapshot of a record with no fund-paid loans. */
        public Snapshot(UUID id, UUID memberId, int levelRank, LocalDate recordSince, int loansOnPlan, String onTimeBand,
                        int defaultsAtCapture, LocalDate capturedOn) {
            this(id, memberId, levelRank, recordSince, loansOnPlan, onTimeBand, defaultsAtCapture, capturedOn, 0);
        }
    }

    record Share(UUID id, UUID memberId, UUID snapshotId, ShareDetail detail, String keyId, String idempotencyKey,
                 String fingerprint, Instant issuedAt, Instant expiresAt, Instant revokedAt) {
        public boolean revoked() {
            return revokedAt != null;
        }
    }

    /** Serialises share creation for one member until the transaction ends. */
    void lock(UUID memberId);

    void insert(Snapshot snapshot, Share share);

    Optional<Share> share(UUID shareId);

    Optional<Share> byIdempotencyKey(UUID memberId, String idempotencyKey);

    Optional<Snapshot> snapshot(UUID snapshotId);

    /** Newest first. */
    List<Share> sharesOf(UUID memberId);

    int activeCount(UUID memberId, Instant now);

    /** True when the share exists and belongs to the member; revoking an already revoked share is fine. */
    boolean revoke(UUID memberId, UUID shareId, Instant at);

    /** Revokes every share of the member that is still active at {@code at}; returns how many it closed. Idempotent. */
    int revokeAllActive(UUID memberId, Instant at);
}
