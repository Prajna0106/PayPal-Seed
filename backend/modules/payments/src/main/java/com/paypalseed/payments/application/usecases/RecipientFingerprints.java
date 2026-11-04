package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * A keyed fingerprint of where a payout goes (ADR 0027): HMAC-SHA256 of the normalised recipient under a server secret,
 * as 64 hex characters. Two payouts to the same recipient have the same fingerprint, so fraud can see a change of
 * recipient; without the secret a fingerprint cannot be recomputed from a guessed email, which a plain hash could be.
 * The recipient itself is not stored, logged or published anywhere because of this class.
 *
 * <p>The secret is {@code seed.payments.recipient-fingerprint-key} ({@code SEED_PAYMENTS_RECIPIENT_FINGERPRINT_KEY}) and
 * must be the same on every instance, or one recipient would have two fingerprints. Outside the dev, test and demo
 * profiles an empty key stops the start. Those profiles use a random key per process, which only means that a restart
 * forgets the recipients seen so far. Changing the key makes every member's next payout look like a change of recipient
 * once; that costs an officer a glance at most, because a change is only a signal close to a disbursement.
 */
@Component
class RecipientFingerprints {

    static final String KEY_PROPERTY = "seed.payments.recipient-fingerprint-key";
    private static final int MIN_KEY_LENGTH = 32;
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "test", "demo");
    private static final Logger log = LoggerFactory.getLogger(RecipientFingerprints.class);

    private final byte[] key;

    @Autowired
    RecipientFingerprints(@Value("${" + KEY_PROPERTY + ":}") String configuredKey, Environment environment) {
        this(resolve(configuredKey, environment));
    }

    /** For tests and callers that hold the key already. */
    RecipientFingerprints(byte[] key) {
        this.key = Objects.requireNonNull(key).clone();
    }

    String of(RecipientType type, String recipient) {
        Mac mac = mac();
        mac.update(normalise(type, recipient).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal());
    }

    /**
     * What makes two spellings of one recipient equal: an email ignores case and surrounding space, a phone number keeps
     * only its digits and a leading plus, a PayPal payer id ignores case. The type is part of the identity, so an email
     * and a phone number never match each other even for one person.
     */
    static String normalise(RecipientType type, String recipient) {
        String value = recipient.strip();
        String normalised = switch (type) {
            case EMAIL -> value.toLowerCase(Locale.ROOT);
            case PHONE -> (value.startsWith("+") ? "+" : "") + value.replaceAll("[^0-9]", "");
            case PAYPAL_ID -> value.toUpperCase(Locale.ROOT);
        };
        return type.name() + ":" + normalised;
    }

    private Mac mac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static byte[] resolve(String configuredKey, Environment environment) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            if (configuredKey.strip().length() < MIN_KEY_LENGTH) {
                throw new IllegalStateException(KEY_PROPERTY + " must be at least " + MIN_KEY_LENGTH + " characters");
            }
            return configuredKey.strip().getBytes(StandardCharsets.UTF_8);
        }
        boolean development = Arrays.stream(environment.getActiveProfiles())
                .map(p -> p.toLowerCase(Locale.ROOT)).anyMatch(DEVELOPMENT_PROFILES::contains);
        if (!development) {
            throw new IllegalStateException(KEY_PROPERTY + " (SEED_PAYMENTS_RECIPIENT_FINGERPRINT_KEY) must be set, to the same value"
                    + " on every instance, outside the dev, test and demo profiles; it keys the fingerprint of a payout recipient that"
                    + " fraud compares, so use at least " + MIN_KEY_LENGTH + " random characters (openssl rand -base64 48)");
        }
        log.info("{} is not set: recipient fingerprints use a key that lasts until this process restarts", KEY_PROPERTY);
        byte[] random = new byte[MIN_KEY_LENGTH];
        new SecureRandom().nextBytes(random);
        return random;
    }
}
