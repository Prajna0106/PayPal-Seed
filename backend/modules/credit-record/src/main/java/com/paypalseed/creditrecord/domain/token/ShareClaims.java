package com.paypalseed.creditrecord.domain.token;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareDetail;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a share token says: which share and snapshot it points at, when it was issued and when it stops working,
 * and how much to show. No member id and no personal data. Seconds precision, so a token and its database row
 * agree exactly.
 */
public record ShareClaims(UUID shareId, UUID snapshotId, Instant issuedAt, Instant expiresAt, ShareDetail detail) {

    /** 16 + 16 + 8 + 8 + 1 bytes. A payload of any other length is refused before anything else is read. */
    static final int ENCODED_LENGTH = 49;

    public ShareClaims {
        Objects.requireNonNull(shareId, "shareId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(detail, "detail");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt").truncatedTo(ChronoUnit.SECONDS);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt").truncatedTo(ChronoUnit.SECONDS);
    }

    byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(ENCODED_LENGTH);
        b.putLong(shareId.getMostSignificantBits()).putLong(shareId.getLeastSignificantBits());
        b.putLong(snapshotId.getMostSignificantBits()).putLong(snapshotId.getLeastSignificantBits());
        b.putLong(issuedAt.getEpochSecond()).putLong(expiresAt.getEpochSecond());
        b.put((byte) (detail.ordinal() + 1));
        return b.array();
    }

    static Optional<ShareClaims> decode(byte[] bytes) {
        if (bytes.length != ENCODED_LENGTH) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(bytes);
        UUID share = new UUID(b.getLong(), b.getLong());
        UUID snapshot = new UUID(b.getLong(), b.getLong());
        long issued = b.getLong();
        long expires = b.getLong();
        int code = b.get() - 1;
        ShareDetail[] all = ShareDetail.values();
        if (code < 0 || code >= all.length || expires <= issued || issued < 0 || expires > Instant.MAX.getEpochSecond() / 2) {
            return Optional.empty();
        }
        return Optional.of(new ShareClaims(share, snapshot, Instant.ofEpochSecond(issued), Instant.ofEpochSecond(expires),
                all[code]));
    }
}
