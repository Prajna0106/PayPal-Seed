package com.paypalseed.deskquery.domain.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.platform.kernel.DomainException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Opaque, signed page cursors: {@code base64url(json) "." base64url(hmac)}. The signature covers the grid, the sort and
 * the filters, so a cursor from another list, another order or another filter is refused: a keyset position only
 * means something for the query that produced it. The signature is not a security boundary (the caller may read
 * every row anyway); it stops a client building a cursor by hand and replaying one against a different query.
 */
public final class CursorCodec {

    private static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final byte[] key;

    /** A blank key makes a random per-process key: cursors then do not survive a restart or cross instances. */
    public CursorCodec(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            this.key = new byte[32];
            new SecureRandom().nextBytes(this.key);
        } else {
            this.key = configuredKey.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String encode(GridQuery query, List<String> lastKeys) {
        try {
            String body = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(JSON.writeValueAsBytes(Map.of("v", VERSION, "k", lastKeys)));
            return body + "." + sign(body, query);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cursor could not be encoded", e);
        }
    }

    /** The sort values of the last row of the previous page; throws {@code desk-query-invalid-cursor} otherwise. */
    public List<String> decode(String token, GridQuery query) {
        int dot = token.indexOf('.');
        if (dot < 1 || dot == token.length() - 1) {
            throw invalid();
        }
        String body = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        if (!MessageDigest.isEqual(sign(body, query).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw invalid();
        }
        try {
            Map<String, Object> payload = JSON.readValue(Base64.getUrlDecoder().decode(body), MAP);
            if (!Integer.valueOf(VERSION).equals(payload.get("v")) || !(payload.get("k") instanceof List<?> raw)) {
                throw invalid();
            }
            return raw.stream().map(o -> {
                if (o instanceof String s) {
                    return s;
                }
                throw invalid();
            }).toList();
        } catch (IOException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private String sign(String body, GridQuery query) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            // Length-prefixed fields cannot be shifted into one another.
            for (String part : new String[] {query.grid(), query.sortSpec(), query.filterSpec(), body}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                mac.update((byte) (bytes.length >>> 24));
                mac.update((byte) (bytes.length >>> 16));
                mac.update((byte) (bytes.length >>> 8));
                mac.update((byte) bytes.length);
                mac.update(bytes);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static DomainException invalid() {
        return new DomainException(DeskQueryErrors.INVALID_CURSOR, "The cursor does not belong to this request");
    }
}
