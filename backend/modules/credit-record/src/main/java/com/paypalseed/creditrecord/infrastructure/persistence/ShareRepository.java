package com.paypalseed.creditrecord.infrastructure.persistence;

import static com.paypalseed.creditrecord.infrastructure.persistence.jooq.Tables.RECORD_SHARE;
import static com.paypalseed.creditrecord.infrastructure.persistence.jooq.Tables.RECORD_SNAPSHOT;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareDetail;
import com.paypalseed.creditrecord.application.ports.ShareStore;
import com.paypalseed.creditrecord.infrastructure.persistence.jooq.tables.records.RecordShareRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Frozen snapshots and the share links that point at them. A share can only ever be revoked, once. */
@Repository
public class ShareRepository implements ShareStore {

    private final DSLContext dsl;

    ShareRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void lock(UUID memberId) {
        MemberLock.acquire(dsl, memberId);
    }

    @Override
    public void insert(Snapshot s, Share share) {
        dsl.insertInto(RECORD_SNAPSHOT)
                .set(RECORD_SNAPSHOT.ID, s.id())
                .set(RECORD_SNAPSHOT.MEMBER_ID, s.memberId())
                .set(RECORD_SNAPSHOT.LEVEL, s.levelRank())
                .set(RECORD_SNAPSHOT.RECORD_SINCE, s.recordSince())
                .set(RECORD_SNAPSHOT.LOANS_ON_PLAN, s.loansOnPlan())
                .set(RECORD_SNAPSHOT.ON_TIME_BAND, s.onTimeBand())
                .set(RECORD_SNAPSHOT.DEFAULTS_AT_CAPTURE, s.defaultsAtCapture())
                .set(RECORD_SNAPSHOT.CAPTURED_ON, s.capturedOn())
                .execute();
        dsl.insertInto(RECORD_SHARE)
                .set(RECORD_SHARE.ID, share.id())
                .set(RECORD_SHARE.MEMBER_ID, share.memberId())
                .set(RECORD_SHARE.SNAPSHOT_ID, share.snapshotId())
                .set(RECORD_SHARE.DETAIL, share.detail().name())
                .set(RECORD_SHARE.KEY_ID, share.keyId())
                .set(RECORD_SHARE.IDEMPOTENCY_KEY, share.idempotencyKey())
                .set(RECORD_SHARE.FINGERPRINT, share.fingerprint())
                .set(RECORD_SHARE.ISSUED_AT, share.issuedAt())
                .set(RECORD_SHARE.EXPIRES_AT, share.expiresAt())
                .execute();
    }

    @Override
    public Optional<Share> share(UUID shareId) {
        return dsl.selectFrom(RECORD_SHARE).where(RECORD_SHARE.ID.eq(shareId)).fetchOptional(ShareRepository::map);
    }

    @Override
    public Optional<Share> byIdempotencyKey(UUID memberId, String idempotencyKey) {
        return dsl.selectFrom(RECORD_SHARE)
                .where(RECORD_SHARE.MEMBER_ID.eq(memberId).and(RECORD_SHARE.IDEMPOTENCY_KEY.eq(idempotencyKey)))
                .fetchOptional(ShareRepository::map);
    }

    @Override
    public Optional<Snapshot> snapshot(UUID snapshotId) {
        return dsl.selectFrom(RECORD_SNAPSHOT).where(RECORD_SNAPSHOT.ID.eq(snapshotId))
                .fetchOptional(r -> new Snapshot(r.getId(), r.getMemberId(), r.getLevel(), r.getRecordSince(),
                        r.getLoansOnPlan(), r.getOnTimeBand(), r.getDefaultsAtCapture(), r.getCapturedOn()));
    }

    @Override
    public List<Share> sharesOf(UUID memberId) {
        return dsl.selectFrom(RECORD_SHARE).where(RECORD_SHARE.MEMBER_ID.eq(memberId))
                .orderBy(RECORD_SHARE.ISSUED_AT.desc(), RECORD_SHARE.ID).fetch(ShareRepository::map);
    }

    @Override
    public int activeCount(UUID memberId, Instant now) {
        return dsl.fetchCount(RECORD_SHARE, RECORD_SHARE.MEMBER_ID.eq(memberId).and(RECORD_SHARE.REVOKED_AT.isNull())
                .and(RECORD_SHARE.EXPIRES_AT.gt(now)));
    }

    @Override
    public boolean revoke(UUID memberId, UUID shareId, Instant at) {
        if (!dsl.fetchExists(RECORD_SHARE, RECORD_SHARE.ID.eq(shareId).and(RECORD_SHARE.MEMBER_ID.eq(memberId)))) {
            return false;
        }
        dsl.update(RECORD_SHARE).set(RECORD_SHARE.REVOKED_AT, at)
                .where(RECORD_SHARE.ID.eq(shareId).and(RECORD_SHARE.MEMBER_ID.eq(memberId)).and(RECORD_SHARE.REVOKED_AT.isNull()))
                .execute();
        return true;
    }

    private static Share map(RecordShareRecord r) {
        return new Share(r.getId(), r.getMemberId(), r.getSnapshotId(), ShareDetail.valueOf(r.getDetail()), r.getKeyId(),
                r.getIdempotencyKey(), r.getFingerprint(), r.getIssuedAt(), r.getExpiresAt(), r.getRevokedAt());
    }
}
