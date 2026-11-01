package com.paypalseed.creditrecord.application.usecases;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.CreateShare;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareCreated;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareDetail;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.VerifiedRecord;
import com.paypalseed.creditrecord.application.ports.RecordStore;
import com.paypalseed.creditrecord.application.ports.ShareStore;
import com.paypalseed.creditrecord.application.ports.ShareStore.Share;
import com.paypalseed.creditrecord.application.ports.ShareStore.Snapshot;
import com.paypalseed.creditrecord.domain.model.RecordErrors;
import com.paypalseed.creditrecord.domain.model.RecordNotFoundException;
import com.paypalseed.creditrecord.domain.model.RecordState;
import com.paypalseed.creditrecord.domain.token.ShareClaims;
import com.paypalseed.creditrecord.domain.token.ShareTokenCodec;
import com.paypalseed.engines.rules.record.OnTimeBand;
import com.paypalseed.engines.rules.record.RecordLevel;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Share links (ADR 0014). A share freezes the record as a snapshot and hands the member a signed token that names
 * the share and snapshot, nothing about the person. Verification checks the signature and expiry in the token,
 * then the database for revocation and for the same share, snapshot and expiry, and says only what the member
 * chose to show.
 */
@Service
public class ShareService {

    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 30;
    public static final int MAX_ACTIVE_SHARES = 25;
    static final String VERIFY_PATH = "/api/v1/records/verify/";

    private final RecordStore records;
    private final ShareStore shares;
    private final ShareTokenCodec codec;
    private final BusinessClock clock;

    public ShareService(RecordStore records, ShareStore shares, ShareTokenCodec codec, BusinessClock clock) {
        this.records = records;
        this.shares = shares;
        this.codec = codec;
        this.clock = clock;
    }

    @Transactional
    public ShareCreated create(UUID memberId, CreateShare request, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        if (request.expiresInDays() < MIN_DAYS || request.expiresInDays() > MAX_DAYS) {
            throw new DomainException(RecordErrors.SHARE_EXPIRY_INVALID,
                    "A share link lasts between " + MIN_DAYS + " and " + MAX_DAYS + " days");
        }
        ShareDetail detail = request.detail() == null ? ShareDetail.SUMMARY : request.detail();
        String fingerprint = detail.name() + ":" + request.expiresInDays();

        shares.lock(memberId);
        var replay = shares.byIdempotencyKey(memberId, idempotencyKey);
        if (replay.isPresent()) {
            Share existing = replay.get();
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was used with a different request");
            }
            return created(existing);
        }

        Instant now = clock.now().truncatedTo(ChronoUnit.SECONDS);
        if (shares.activeCount(memberId, now) >= MAX_ACTIVE_SHARES) {
            throw new DomainException(RecordErrors.SHARE_LIMIT, "Revoke a share link before making another");
        }
        RecordState state = records.find(memberId)
                .orElseThrow(() -> new DomainException(RecordErrors.NOTHING_TO_SHARE, "Your record has no history yet"));

        // The month, not the day, of the first fact: a partner learns how long, not which day.
        Snapshot snapshot = new Snapshot(Ids.newId(), memberId, state.level().rank(), state.since().withDayOfMonth(1),
                state.lifetime().loansRepaidOnPlan(), OnTimeBand.of(state.lifetime().onTimeBps()).name(), state.defaults(),
                clock.today());
        Share share = new Share(Ids.newId(), memberId, snapshot.id(), detail, codec.signingKeyId(), idempotencyKey,
                fingerprint, now, now.plus(request.expiresInDays(), ChronoUnit.DAYS), null);
        ShareCreated result = created(share);
        shares.insert(snapshot, share);
        return result;
    }

    @Transactional(readOnly = true)
    public List<ShareView> list(UUID memberId) {
        Instant now = clock.now();
        return shares.sharesOf(memberId).stream().map(s -> new ShareView(s.id(), s.detail(), status(s, now), s.issuedAt(),
                s.expiresAt(), s.revokedAt())).toList();
    }

    @Transactional
    public void revoke(UUID memberId, UUID shareId) {
        if (!shares.revoke(memberId, shareId, clock.now())) {
            throw new DomainException(RecordErrors.SHARE_NOT_FOUND, "No such share link");
        }
    }

    /** Every refusal is the same {@link RecordErrors#NOT_FOUND}: the caller cannot tell why. */
    @Transactional(readOnly = true)
    public VerifiedRecord verify(String token) {
        Instant now = clock.now();
        ShareClaims claims = codec.verify(token, now).orElseThrow(ShareService::notFound);
        Share share = shares.share(claims.shareId()).orElseThrow(ShareService::notFound);
        boolean sameShare = share.snapshotId().equals(claims.snapshotId())
                && share.issuedAt().truncatedTo(ChronoUnit.SECONDS).equals(claims.issuedAt())
                && share.expiresAt().truncatedTo(ChronoUnit.SECONDS).equals(claims.expiresAt())
                && share.detail() == claims.detail();
        if (!sameShare || share.revoked() || !now.isBefore(share.expiresAt())) {
            throw notFound();
        }
        Snapshot snapshot = shares.snapshot(share.snapshotId()).orElseThrow(ShareService::notFound);
        if (!snapshot.memberId().equals(share.memberId())) {
            throw notFound();
        }
        // A default after the snapshot makes it out of date; the partner is told, not shown the new facts.
        boolean valid = records.find(share.memberId()).map(r -> r.defaults() == snapshot.defaultsAtCapture()).orElse(false);
        boolean summary = share.detail() != ShareDetail.LEVEL;
        boolean full = share.detail() == ShareDetail.FULL;
        return new VerifiedRecord(valid, RecordLevel.ofRank(snapshot.levelRank()).name(),
                summary ? snapshot.recordSince() : null, summary ? snapshot.loansOnPlan() : null,
                full ? OnTimeBand.valueOf(snapshot.onTimeBand()).label() : null, share.issuedAt(), share.expiresAt());
    }

    private ShareCreated created(Share share) {
        ShareClaims claims = new ShareClaims(share.id(), share.snapshotId(), share.issuedAt(), share.expiresAt(),
                share.detail());
        String token = codec.sign(claims, share.keyId());
        return new ShareCreated(share.id(), token, VERIFY_PATH + token, share.detail(), share.issuedAt(), share.expiresAt());
    }

    private static String status(Share s, Instant now) {
        if (s.revoked()) {
            return "REVOKED";
        }
        return now.isBefore(s.expiresAt()) ? "ACTIVE" : "EXPIRED";
    }

    private static RecordNotFoundException notFound() {
        return new RecordNotFoundException();
    }
}
