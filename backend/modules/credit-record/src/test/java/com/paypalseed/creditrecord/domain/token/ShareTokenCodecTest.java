package com.paypalseed.creditrecord.domain.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareDetail;
import com.paypalseed.platform.kernel.DomainException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShareTokenCodecTest {

    private static final String SECRET_1 = "unit-test-secret-one-0123456789abcdef";
    private static final String SECRET_2 = "unit-test-secret-two-0123456789abcdef";
    private static final Instant ISSUED = Instant.parse("2026-11-10T20:00:00Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(7 * 86_400);
    private static final Instant NOW = ISSUED.plusSeconds(60);
    private static final ShareClaims CLAIMS = new ShareClaims(UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"), ISSUED, EXPIRES, ShareDetail.SUMMARY);

    private final ShareTokenCodec codec = ShareTokenCodec.of("k1", Map.of("k1", SECRET_1));

    private static String replacePart(String token, int index, String value) {
        String[] parts = token.split("\\.");
        parts[index] = value;
        return String.join(".", parts);
    }

    private static String flipFirstChar(String part) {
        return (part.charAt(0) == 'A' ? "B" : "A") + part.substring(1);
    }

    @Test
    void aGenuineTokenVerifiesAndCarriesOnlyTheClaims() {
        String token = codec.sign(CLAIMS, null);

        assertThat(token).startsWith("v1.k1.").hasSizeLessThan(200);
        assertThat(codec.verify(token, NOW)).contains(CLAIMS);
        assertThat(token).doesNotContain(CLAIMS.shareId().toString()); // it is encoded, not spelled out
    }

    @Test
    void signingIsDeterministicSoAnIdempotentReplayGivesTheSameToken() {
        assertThat(codec.sign(CLAIMS, "k1")).isEqualTo(codec.sign(CLAIMS, null));
    }

    @Test
    void expiredTokenFailsAtTheExactSecond() {
        String token = codec.sign(CLAIMS, null);
        assertThat(codec.verify(token, EXPIRES.minusSeconds(1))).isPresent();
        assertThat(codec.verify(token, EXPIRES)).isEmpty();
        assertThat(codec.verify(token, EXPIRES.plusSeconds(86_400))).isEmpty();
    }

    @Test
    void aChangedSignatureFails() {
        String token = codec.sign(CLAIMS, null);
        String[] parts = token.split("\\.");
        assertThat(codec.verify(replacePart(token, 3, flipFirstChar(parts[3])), NOW)).isEmpty();
        assertThat(codec.verify(replacePart(token, 3, parts[3].substring(0, parts[3].length() - 2)), NOW)).isEmpty();
        assertThat(codec.verify(replacePart(token, 3, ""), NOW)).isEmpty();
    }

    @Test
    void aChangedPayloadFailsEvenWithTheOriginalSignature() {
        String token = codec.sign(CLAIMS, null);
        // A longer expiry, re-encoded, with the signature of the real token.
        ShareClaims longer = new ShareClaims(CLAIMS.shareId(), CLAIMS.snapshotId(), ISSUED, EXPIRES.plusSeconds(86_400 * 365L),
                ShareDetail.FULL);
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(longer.encode());

        assertThat(codec.verify(replacePart(token, 2, forgedPayload), NOW)).isEmpty();
        assertThat(codec.verify(replacePart(token, 2, flipFirstChar(token.split("\\.")[2])), NOW)).isEmpty();
    }

    @Test
    void anUnknownOrSwappedKeyIdFails() {
        String token = codec.sign(CLAIMS, null);
        assertThat(codec.verify(replacePart(token, 1, "k9"), NOW)).isEmpty();

        ShareTokenCodec ring = ShareTokenCodec.of("k1", Map.of("k1", SECRET_1, "k2", SECRET_2));
        // Naming a different known key does not help: the signature was made with the first secret.
        assertThat(ring.verify(replacePart(ring.sign(CLAIMS, "k1"), 1, "k2"), NOW)).isEmpty();
    }

    @Test
    void aTokenSignedByAnotherSecretFails() {
        ShareTokenCodec other = ShareTokenCodec.of("k1", Map.of("k1", SECRET_2));
        assertThat(codec.verify(other.sign(CLAIMS, null), NOW)).isEmpty();
    }

    @Test
    void rotationKeepsOldTokensValidWhileTheirKeyIsInTheRingAndStopsThemWhenItLeaves() {
        String oldToken = codec.sign(CLAIMS, null);

        ShareTokenCodec rotated = ShareTokenCodec.of("k2", Map.of("k1", SECRET_1, "k2", SECRET_2));
        assertThat(rotated.verify(oldToken, NOW)).contains(CLAIMS);
        assertThat(rotated.sign(CLAIMS, null)).startsWith("v1.k2.");
        assertThat(rotated.verify(rotated.sign(CLAIMS, null), NOW)).contains(CLAIMS);

        ShareTokenCodec retired = ShareTokenCodec.of("k2", Map.of("k2", SECRET_2));
        assertThat(retired.verify(oldToken, NOW)).isEmpty();
    }

    @Test
    void algorithmConfusionAttemptsAreNotTokens() {
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        String genuinePayload = codec.sign(CLAIMS, null).split("\\.")[2];

        assertThat(codec.verify(b64 + "." + body + ".", NOW)).isEmpty();                 // JWT with alg none
        assertThat(codec.verify(b64 + "." + body + ".AAAA", NOW)).isEmpty();            // JWT with a junk signature
        assertThat(codec.verify("v1.none." + genuinePayload + ".", NOW)).isEmpty();     // a key id of "none"
        assertThat(codec.verify("v1.k1." + genuinePayload + ".", NOW)).isEmpty();       // genuine payload, no signature
        assertThat(codec.verify("v0.k1." + genuinePayload + ".AAAA", NOW)).isEmpty();   // another version
        assertThat(codec.verify("HS256.k1." + genuinePayload + ".AAAA", NOW)).isEmpty();
        // The signature of an empty key (what a "none" verifier would accept) is not accepted either.
        assertThat(codec.verify("v1.k1." + genuinePayload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]), NOW))
                .isEmpty();
    }

    @Test
    void malformedInputIsRefusedWithoutAnError() {
        String token = codec.sign(CLAIMS, null);
        assertThat(codec.verify(null, NOW)).isEmpty();
        assertThat(codec.verify("", NOW)).isEmpty();
        assertThat(codec.verify("....", NOW)).isEmpty();
        assertThat(codec.verify(token + ".extra", NOW)).isEmpty();
        assertThat(codec.verify(token.substring(0, token.lastIndexOf('.')), NOW)).isEmpty();
        assertThat(codec.verify("x".repeat(ShareTokenCodec.MAX_TOKEN_LENGTH + 1), NOW)).isEmpty();
        assertThat(codec.verify("v1.k1.!!!.!!!", NOW)).isEmpty();
        assertThat(codec.verify("v1.k 1.AAAA.AAAA", NOW)).isEmpty();
    }

    @Test
    void aPayloadWithTheWrongLengthOrDetailIsRefusedEvenWhenSigned() {
        // Signed correctly with the real secret, so only the payload check can stop it.
        byte[] short49 = new byte[10];
        String head = "v1.k1." + Base64.getUrlEncoder().withoutPadding().encodeToString(short49);
        String signed = head + "." + signOf(head);
        assertThat(codec.verify(signed, NOW)).isEmpty();

        byte[] badDetail = CLAIMS.encode();
        badDetail[badDetail.length - 1] = 9;
        String head2 = "v1.k1." + Base64.getUrlEncoder().withoutPadding().encodeToString(badDetail);
        assertThat(codec.verify(head2 + "." + signOf(head2), NOW)).isEmpty();
    }

    @Test
    void claimsKeepOnlySecondsAndRejectBackwardsExpiry() {
        ShareClaims c = new ShareClaims(CLAIMS.shareId(), CLAIMS.snapshotId(), ISSUED.plusMillis(900), EXPIRES.plusMillis(100),
                ShareDetail.LEVEL);
        assertThat(c.issuedAt()).isEqualTo(ISSUED);
        assertThat(c.expiresAt()).isEqualTo(EXPIRES);
        assertThat(ShareClaims.decode(c.encode())).contains(c);
        ShareClaims backwards = new ShareClaims(CLAIMS.shareId(), CLAIMS.snapshotId(), EXPIRES, ISSUED, ShareDetail.LEVEL);
        assertThat(ShareClaims.decode(backwards.encode())).isEmpty();
    }

    @Test
    void keysMustBeLongEnoughAndNamedSafely() {
        assertThatThrownBy(() -> ShareTokenCodec.of("k1", Map.of("k1", "short"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShareTokenCodec.of("bad.id", Map.of("bad.id", SECRET_1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShareTokenCodec.of("k2", Map.of("k1", SECRET_1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutAKeySigningAndVerifyingReportTheDependencyUnavailable() {
        ShareTokenCodec none = ShareTokenCodec.unconfigured();
        assertThat(none.configured()).isFalse();
        assertThatThrownBy(() -> none.sign(CLAIMS, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> none.verify("v1.k1.a.b", NOW)).isInstanceOf(DomainException.class);
    }

    private static String signOf(String head) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET_1.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(head.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
