package com.paypalseed.fraud.application.usecases;

import com.paypalseed.fraud.infrastructure.persistence.FactRepository;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Turns a stable identifier (a PayPal payer id, a payout recipient) into a salted HMAC-SHA256, so two facts can be matched
 * without the identifier ever being stored. The salt is created by the database migration and stays in the fraud schema.
 */
@Component
public class ReferenceHasher {

    private final FactRepository facts;
    private volatile byte[] salt;

    ReferenceHasher(FactRepository facts) {
        this.facts = facts;
    }

    /** Null or blank means there is no reference. */
    public String hash(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(reference.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required on every JVM", e);
        }
    }

    private byte[] salt() {
        byte[] known = salt;
        if (known == null) {
            known = facts.salt().getBytes(StandardCharsets.UTF_8);
            salt = known;
        }
        return known;
    }
}
