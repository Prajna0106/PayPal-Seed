package com.paypalseed.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds tokens a well-behaved issuer would never produce, so every rejection path can be driven. */
final class TokenForge {

    static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    static final String SECRET_1 = "k1-secret-0123456789-0123456789-0123456789-0123456789-0123456789-ab";
    static final String SECRET_2 = "k2-secret-9876543210-9876543210-9876543210-9876543210-9876543210-cd";

    private TokenForge() {
    }

    /** Claims of a valid one-minute member token issued at {@link #NOW}. */
    static Map<String, Object> member(UUID id) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ActorTokenVerifier.ISSUER);
        claims.put("aud", ActorTokenVerifier.AUDIENCE);
        claims.put("sub", id.toString());
        claims.put("act", "member");
        claims.put("iat", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(60).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        return claims;
    }

    static Map<String, Object> staff(UUID id, String... roles) {
        Map<String, Object> claims = member(id);
        claims.put("act", "staff");
        claims.put("roles", List.of(roles));
        return claims;
    }

    static String sign(Map<String, Object> claims, String kid, String secret) {
        return sign(JWSAlgorithm.HS256, JOSEObjectType.JWT, kid, claims, secret);
    }

    static String sign(JWSAlgorithm alg, JOSEObjectType typ, String kid, Map<String, Object> claims, String secret) {
        JWSHeader.Builder header = new JWSHeader.Builder(alg);
        if (typ != null) {
            header.type(typ);
        }
        if (kid != null) {
            header.keyID(kid);
        }
        JWSObject jws = new JWSObject(header.build(), new Payload(JSONObjectUtils.toJSONString(claims)));
        try {
            jws.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jws.serialize();
    }

    /** {@code alg: none}: header and payload, empty signature. */
    static String unsigned(Map<String, Object> claims) {
        return encode("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + encode(JSONObjectUtils.toJSONString(claims)) + ".";
    }

    /** Header chosen freely; the signature is a real HMAC-SHA256 over those exact bytes. */
    static String withHeader(String headerJson, Map<String, Object> claims, String secret) {
        return withRawPayload(headerJson, JSONObjectUtils.toJSONString(claims), secret);
    }

    static String withRawPayload(String headerJson, String payload, String secret) {
        String signingInput = encode(headerJson) + "." + encode(payload);
        return signingInput + "." + hmac(signingInput, secret);
    }

    private static String hmac(String signingInput, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64URL.encode(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII))).toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String encode(String json) {
        return Base64URL.encode(json.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
