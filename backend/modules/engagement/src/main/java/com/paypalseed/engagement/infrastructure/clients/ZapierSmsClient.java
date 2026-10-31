package com.paypalseed.engagement.infrastructure.clients;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Outbound SMS go to a Zapier Catch Hook, signed with HMAC-SHA256 over {@code timestamp.body} so
 * the Zap can reject forgeries. Inbound replies are verified the same way. With no hook configured,
 * messages are only logged.
 */
@Component
public class ZapierSmsClient {

    private static final Logger log = LoggerFactory.getLogger(ZapierSmsClient.class);
    private static final long MAX_SKEW_SECONDS = 300;

    private final RestClient http;
    private final ObjectMapper json;
    private final String hookUrl;
    private final byte[] secret;

    public ZapierSmsClient(ObjectMapper json, @Value("${seed.zapier.sms-hook-url:}") String hookUrl,
                           @Value("${seed.zapier.signing-secret:}") String secret) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
        this.json = json;
        this.hookUrl = hookUrl;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public void send(String phoneE164, String body, String dedupeKey) {
        if (hookUrl.isBlank()) {
            log.info("[dry-run SMS] {} -> {}", dedupeKey, body);
            return;
        }
        String payload;
        try {
            payload = json.writeValueAsString(Map.of("to", phoneE164, "body", body, "dedupeKey", dedupeKey));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise SMS payload", e);
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        http.post().uri(hookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Seed-Timestamp", timestamp)
                .header("X-Seed-Signature", sign(timestamp + "." + payload))
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    /** Verifies an inbound Zapier request: fresh timestamp and a matching signature, compared in constant time. */
    public boolean verify(String timestamp, String signature, String body) {
        if (secret.length == 0 || timestamp == null || signature == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > MAX_SKEW_SECONDS) {
            return false;
        }
        byte[] expected = sign(timestamp + "." + body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }

    public String sign(String message) {
        if (secret.length == 0) {
            throw new IllegalStateException("seed.zapier.signing-secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
