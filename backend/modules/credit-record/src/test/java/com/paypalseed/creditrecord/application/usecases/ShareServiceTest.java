package com.paypalseed.creditrecord.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.CreateShare;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareCreated;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareDetail;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.VerifiedRecord;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.application.ports.ShareStore.Share;
import com.paypalseed.creditrecord.domain.model.RecordErrors;
import com.paypalseed.creditrecord.domain.token.ShareClaims;
import com.paypalseed.creditrecord.domain.token.ShareTokenCodec;
import com.paypalseed.creditrecord.support.InMemoryStores;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.testing.FakeBusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShareServiceTest {

    private static final String SECRET = "unit-test-secret-one-0123456789abcdef";
    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID LOAN = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final InMemoryStores.Records records = new InMemoryStores.Records();
    private final InMemoryStores.Shares shares = new InMemoryStores.Shares();
    private final FakeBusinessClock clock = new FakeBusinessClock(LocalDate.of(2026, 11, 10));
    private final ShareTokenCodec codec = ShareTokenCodec.of("k1", Map.of("k1", SECRET));
    private ShareService service;
    private RecordIngest ingest;

    @BeforeEach
    void setUp() {
        service = new ShareService(records, shares, codec, clock);
        ingest = new RecordIngest(records, event -> {
        });
        for (int month = 1; month <= 3; month++) {
            ingest.ingest(RecordFact.savingsDeposit("dep-ana-" + month, ANA, LocalDate.of(2026, month, 5)));
        }
        ingest.ingest(RecordFact.loanRepaid(ANA, LOAN, 0, LocalDate.of(2026, 9, 1)));
    }

    private ShareCreated create(ShareDetail detail, int days, String key) {
        return service.create(ANA, new CreateShare(days, detail), key);
    }

    @Test
    void aSharedRecordVerifiesAndShowsOnlyWhatWasChosen() {
        VerifiedRecord level = service.verify(create(ShareDetail.LEVEL, 7, "k-level").token());
        assertThat(level.valid()).isTrue();
        assertThat(level.level()).isEqualTo("BUILDING");
        assertThat(level.since()).isNull();
        assertThat(level.loansRepaidOnPlan()).isNull();
        assertThat(level.onTimeBand()).isNull();

        VerifiedRecord summary = service.verify(create(ShareDetail.SUMMARY, 7, "k-summary").token());
        assertThat(summary.since()).isEqualTo(LocalDate.of(2026, 1, 1)); // the month, not the day
        assertThat(summary.loansRepaidOnPlan()).isEqualTo(1);
        assertThat(summary.onTimeBand()).isNull();

        VerifiedRecord full = service.verify(create(ShareDetail.FULL, 7, "k-full").token());
        assertThat(full.onTimeBand()).isEqualTo("95-100%");
        assertThat(full.issuedAt()).isEqualTo(Instant.parse("2026-11-10T20:00:00Z"));
        assertThat(full.expiresAt()).isEqualTo(Instant.parse("2026-11-17T20:00:00Z"));
    }

    @Test
    void theVerifiedAnswerHasNoFieldThatCouldNameThePerson() {
        assertThat(VerifiedRecord.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("valid", "level", "since", "loansRepaidOnPlan", "onTimeBand", "issuedAt", "expiresAt");
    }

    @Test
    void theTokenCarriesNoMemberId() {
        String token = create(ShareDetail.FULL, 7, "k-1").token();
        assertThat(token).doesNotContain(ANA.toString());
        ShareClaims claims = codec.verify(token, clock.now()).orElseThrow();
        assertThat(shares.share(claims.shareId()).orElseThrow().memberId()).isEqualTo(ANA);
    }

    @Test
    void aShareLastsAtMostThirtyDaysAndAtLeastOne() {
        assertThat(create(ShareDetail.LEVEL, 30, "k-30").expiresAt()).isEqualTo(Instant.parse("2026-12-10T20:00:00Z"));
        assertThat(create(ShareDetail.LEVEL, 1, "k-1").expiresAt()).isEqualTo(Instant.parse("2026-11-11T20:00:00Z"));
        for (int days : new int[] {0, 31, -1, 365}) {
            assertThatThrownBy(() -> create(ShareDetail.LEVEL, days, "k-bad-" + days))
                    .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(RecordErrors.SHARE_EXPIRY_INVALID));
        }
    }

    @Test
    void anExpiredShareIsNotFound() {
        String token = create(ShareDetail.SUMMARY, 2, "k-1").token();
        clock.set(LocalDate.of(2026, 11, 11));
        assertThat(service.verify(token).valid()).isTrue();
        clock.set(LocalDate.of(2026, 11, 13));
        assertNotFound(token);
    }

    @Test
    void aRevokedShareIsNotFoundAndTheOwnerCanRevokeTwice() {
        ShareCreated created = create(ShareDetail.SUMMARY, 7, "k-1");
        assertThat(service.verify(created.token()).valid()).isTrue();

        service.revoke(ANA, created.shareId());
        service.revoke(ANA, created.shareId());

        assertNotFound(created.token());
        assertThat(service.list(ANA)).singleElement().satisfies(s -> {
            assertThat(s.status()).isEqualTo("REVOKED");
            assertThat(s.revokedAt()).isNotNull();
        });
    }

    @Test
    void anotherMemberCannotRevokeOrLearnThatTheShareExists() {
        ShareCreated created = create(ShareDetail.SUMMARY, 7, "k-1");
        assertThatThrownBy(() -> service.revoke(BEN, created.shareId()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(RecordErrors.SHARE_NOT_FOUND));
        assertThatThrownBy(() -> service.revoke(ANA, UUID.randomUUID()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(RecordErrors.SHARE_NOT_FOUND));
        assertThat(service.verify(created.token()).valid()).isTrue();
        assertThat(service.list(BEN)).isEmpty();
    }

    @Test
    void everyKindOfBadTokenGivesTheSameNotFound() {
        String token = create(ShareDetail.SUMMARY, 7, "k-1").token();
        String[] parts = token.split("\\.");
        List<String> bad = List.of("", "garbage", token + "x", parts[0] + "." + parts[1] + "." + parts[2] + ".AAAA",
                parts[0] + ".k7." + parts[2] + "." + parts[3], "v1.k1.AAAA.AAAA");
        for (String candidate : bad) {
            assertNotFound(candidate);
        }
    }

    @Test
    void aTokenSignedForAnUnknownShareIsNotFound() {
        ShareClaims stray = new ShareClaims(UUID.randomUUID(), UUID.randomUUID(), clock.now(), clock.now().plusSeconds(3600),
                ShareDetail.FULL);
        assertNotFound(codec.sign(stray, null));
    }

    @Test
    void anOldSnapshotCannotBeReplayedUnderAnotherShare() {
        ShareCreated first = create(ShareDetail.FULL, 7, "k-1");
        ShareCreated second = create(ShareDetail.LEVEL, 7, "k-2");
        ShareClaims a = codec.verify(first.token(), clock.now()).orElseThrow();
        ShareClaims b = codec.verify(second.token(), clock.now()).orElseThrow();

        // A genuine signature, but over share B's id with snapshot A: the database says that is not B's snapshot.
        ShareClaims spliced = new ShareClaims(b.shareId(), a.snapshotId(), b.issuedAt(), b.expiresAt(), b.detail());
        assertNotFound(codec.sign(spliced, null));
        // Widening the detail of a LEVEL share is refused for the same reason.
        ShareClaims widened = new ShareClaims(b.shareId(), b.snapshotId(), b.issuedAt(), b.expiresAt(), ShareDetail.FULL);
        assertNotFound(codec.sign(widened, null));
        // Pushing the expiry out is refused too.
        ShareClaims longer = new ShareClaims(b.shareId(), b.snapshotId(), b.issuedAt(), b.expiresAt().plusSeconds(86_400), b.detail());
        assertNotFound(codec.sign(longer, null));
    }

    @Test
    void aDefaultAfterTheSnapshotMakesItInvalidButNeverShowsTheNewFacts() {
        String token = create(ShareDetail.FULL, 7, "k-1").token();
        assertThat(service.verify(token).valid()).isTrue();

        ingest.ingest(RecordFact.loanDefaulted(ANA, LOAN, LocalDate.of(2026, 11, 20)));

        VerifiedRecord after = service.verify(token);
        assertThat(after.valid()).isFalse();
        assertThat(after.level()).isEqualTo("BUILDING"); // what was shared, not the live level
    }

    @Test
    void theSameKeyAndBodyReturnsTheSameShareAndADifferentBodyIsRefused() {
        ShareCreated first = create(ShareDetail.SUMMARY, 7, "k-1");
        ShareCreated again = create(ShareDetail.SUMMARY, 7, "k-1");

        assertThat(again).isEqualTo(first);
        assertThat(shares.shares).hasSize(1);
        assertThatThrownBy(() -> create(ShareDetail.FULL, 7, "k-1"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.IDEMPOTENCY_KEY_REUSED));
        assertThatThrownBy(() -> create(ShareDetail.SUMMARY, 8, "k-1"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void aMemberHoldsAtMostTwentyFiveActiveShares() {
        IntStream.range(0, ShareService.MAX_ACTIVE_SHARES).forEach(i -> create(ShareDetail.LEVEL, 7, "k-" + i));
        assertThatThrownBy(() -> create(ShareDetail.LEVEL, 7, "k-over"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(RecordErrors.SHARE_LIMIT));
        service.revoke(ANA, service.list(ANA).get(0).shareId());
        assertThat(create(ShareDetail.LEVEL, 7, "k-after").token()).isNotBlank();
    }

    @Test
    void thereIsNothingToShareBeforeTheFirstFact() {
        assertThatThrownBy(() -> service.create(BEN, new CreateShare(7, ShareDetail.LEVEL), "k-1"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(RecordErrors.NOTHING_TO_SHARE));
    }

    @Test
    void listingShowsStatusButNeverTheToken() {
        create(ShareDetail.SUMMARY, 1, "k-1");
        clock.set(LocalDate.of(2026, 11, 12));
        create(ShareDetail.FULL, 7, "k-2");

        assertThat(service.list(ANA)).extracting(s -> s.status()).containsExactly("ACTIVE", "EXPIRED");
        assertThat(service.list(ANA).toString()).doesNotContain("v1.k1.");
    }

    @Test
    void withoutASigningKeySharingReportsTheDependencyUnavailable() {
        ShareService off = new ShareService(records, shares, ShareTokenCodec.unconfigured(), clock);
        assertThatThrownBy(() -> off.create(ANA, new CreateShare(7, ShareDetail.LEVEL), "k-1"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.DEPENDENCY_UNAVAILABLE));
        assertThat(shares.shares).isEmpty();
        assertThatThrownBy(() -> off.verify("v1.k1.a.b")).isInstanceOf(DomainException.class);
    }

    @Test
    void aRowThatWasRevokedOrAlteredInTheStoreStopsVerifying() {
        ShareCreated created = create(ShareDetail.SUMMARY, 7, "k-1");
        Share row = shares.shares.get(created.shareId());
        shares.shares.put(row.id(), new Share(row.id(), row.memberId(), row.snapshotId(), row.detail(), row.keyId(),
                row.idempotencyKey(), row.fingerprint(), row.issuedAt(), row.expiresAt().minusSeconds(3600), null));
        assertNotFound(created.token());
    }

    private void assertNotFound(String token) {
        assertThatThrownBy(() -> service.verify(token)).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(RecordErrors.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo("No such record");
            assertThat(e.details()).isEmpty();
        });
    }
}
