package com.paypalseed.creditrecord.infrastructure;

import com.paypalseed.creditrecord.domain.token.ShareTokenCodec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * The key ring for share tokens. {@code SEED_RECORD_SIGNING_SECRET} signs new tokens under
 * {@code SEED_RECORD_SIGNING_KEY_ID} (default {@code k1}). To rotate: give the new secret a new key id, and list the
 * old one in {@code SEED_RECORD_PREVIOUS_KEYS} as {@code kid:secret,kid2:secret2} until its tokens expire (30 days).
 *
 * <p>With no secret, dev, test and demo get a random one that lasts until restart. Anywhere else sharing is off
 * (503) rather than signed with a guessable key; a random key per process would break verification across replicas.
 */
@Configuration(proxyBeanMethods = false)
class ShareSigningConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ShareSigningConfiguration.class);

    @Bean
    ShareTokenCodec shareTokenCodec(
            @Value("${seed.record.signing-secret:${SEED_RECORD_SIGNING_SECRET:}}") String secret,
            @Value("${seed.record.signing-key-id:${SEED_RECORD_SIGNING_KEY_ID:k1}}") String keyId,
            @Value("${seed.record.previous-keys:${SEED_RECORD_PREVIOUS_KEYS:}}") String previous,
            Environment environment) {
        String signing = secret == null ? "" : secret.trim();
        if (signing.isEmpty()) {
            if (!environment.acceptsProfiles(Profiles.of("dev", "test", "demo"))) {
                log.warn("SEED_RECORD_SIGNING_SECRET is not set: record share links are switched off");
                return ShareTokenCodec.unconfigured();
            }
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            signing = Base64.getEncoder().encodeToString(random);
            log.warn("SEED_RECORD_SIGNING_SECRET is not set: using a random key that lasts until restart");
        }
        Map<String, String> ring = new LinkedHashMap<>();
        for (String entry : previous == null ? new String[0] : previous.split(",")) {
            int colon = entry.indexOf(':');
            if (!entry.isBlank() && colon > 0) {
                ring.put(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
            }
        }
        ring.put(keyId.trim(), signing);
        return ShareTokenCodec.of(keyId.trim(), ring);
    }
}
