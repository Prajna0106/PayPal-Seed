package com.paypalseed.creditrecord.domain.token;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies share tokens: {@code v1.<keyId>.<payload>.<signature>}, both parts base64url without padding.
 *
 * <p>The algorithm is not in the token. It is HMAC-SHA256, fixed here, so a token cannot choose {@code none}, a
 * weaker hash or a public-key trick, and a token with a different shape is simply not parsed. The signature covers
 * the version, the key id and the payload. The key id picks the secret, so a retired key stops verifying the moment
 * it leaves the ring, while a rotated-in key signs new tokens and the previous keys still verify old ones.
 */
public final class ShareTokenCodec {

    static final String VERSION = "v1";
    static final int MAX_TOKEN_LENGTH = 400;
    static final int MIN_SECRET_LENGTH = 32;
    private static final String HMAC = "HmacSHA256";
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODE = Base64.getUrlDecoder();

    private final String signingKeyId;
    private final Map<String, byte[]> secrets;

    private ShareTokenCodec(String signingKeyId, Map<String, byte[]> secrets) {
        this.signingKeyId = signingKeyId;
        this.secrets = secrets;
    }

    /** A codec with no key: signing and verifying both report the dependency as unavailable. */
    public static ShareTokenCodec unconfigured() {
        return new ShareTokenCodec(null, Map.of());
    }

    /**
     * @param signingKeyId the key new tokens are signed with
     * @param secrets      every key that may verify, including the signing key (key id to secret)
     */
    public static ShareTokenCodec of(String signingKeyId, Map<String, String> secrets) {
        Objects.requireNonNull(signingKeyId, "signingKeyId");
        Map<String, byte[]> keys = new LinkedHashMap<>();
        secrets.forEach((id, secret) -> {
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("A signing key id is 1 to 16 letters, digits, '-' or '_'");
            }
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH) {
                throw new IllegalArgumentException("Signing key " + id + " must be at least " + MIN_SECRET_LENGTH + " bytes");
            }
            keys.put(id, secret.getBytes(StandardCharsets.UTF_8));
        });
        if (!keys.containsKey(signingKeyId)) {
            throw new IllegalArgumentException("The signing key id " + signingKeyId + " has no secret");
        }
        return new ShareTokenCodec(signingKeyId, Map.copyOf(keys));
    }

    public boolean configured() {
        return signingKeyId != null;
    }

    public String signingKeyId() {
        return signingKeyId;
    }

    public boolean knows(String keyId) {
        return secrets.containsKey(keyId);
    }

    /** Signs with the current key, or with {@code keyId} when it is still in the ring (an idempotent replay). */
    public String sign(ShareClaims claims, String keyId) {
        requireConfigured();
        String kid = keyId != null && secrets.containsKey(keyId) ? keyId : signingKeyId;
        String head = VERSION + "." + kid + "." + B64.encodeToString(claims.encode());
        return head + "." + B64.encodeToString(mac(secrets.get(kid), head));
    }

    /**
     * The claims of a genuine, unexpired token, or empty. Every kind of failure looks the same to the caller: the
     * wrong shape, an unknown key id, a bad signature, a changed payload, an expired token.
     */
    public Optional<ShareClaims> verify(String token, Instant now) {
        requireConfigured();
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !KEY_ID.matcher(parts[1]).matches()) {
            return Optional.empty();
        }
        byte[] secret = secrets.get(parts[1]);
        byte[] given = decode(parts[3]);
        byte[] payload = decode(parts[2]);
        if (secret == null || given == null || payload == null) {
            return Optional.empty();
        }
        byte[] expected = mac(secret, parts[0] + "." + parts[1] + "." + parts[2]);
        if (!MessageDigest.isEqual(expected, given)) {
            return Optional.empty();
        }
        return ShareClaims.decode(payload).filter(c -> now.isBefore(c.expiresAt()));
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE,
                    "Record sharing is not set up on this server");
        }
    }

    private static byte[] decode(String part) {
        if (part.isEmpty()) {
            return null;
        }
        try {
            return B64_DECODE.decode(part);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] mac(byte[] secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
