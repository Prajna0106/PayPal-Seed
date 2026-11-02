package com.paypalseed.platform.security;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The HS256 keys that sign and verify actor tokens, in rotation order. The first key signs (the
 * issuer helper) and every listed key verifies, so a new key can be added to the verifiers before
 * the web servers start signing with it, and the old key removed after its last token expired.
 */
public final class ActorTokenKeys {

    public static final int MIN_SECRET_CHARS = 32;
    private static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private final Map<String, byte[]> secrets;

    private ActorTokenKeys(Map<String, byte[]> secrets) {
        this.secrets = Collections.unmodifiableMap(secrets);
    }

    public static ActorTokenKeys none() {
        return new ActorTokenKeys(new LinkedHashMap<>());
    }

    /**
     * Parses {@code kid:secret} entries. The secret is everything after the first colon. Error
     * messages name the entry position or the kid, never the secret.
     */
    public static ActorTokenKeys parse(List<String> entries) {
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        int position = 0;
        for (String raw : entries) {
            position++;
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                throw new IllegalStateException("Actor token secret " + position + " must be written kid:secret");
            }
            String kid = entry.substring(0, colon);
            String secret = entry.substring(colon + 1);
            if (!KID.matcher(kid).matches()) {
                throw new IllegalStateException("Actor token secret " + position
                        + " has an invalid kid (1 to 32 of letters, digits, dot, dash, underscore)");
            }
            if (secret.length() < MIN_SECRET_CHARS) {
                throw new IllegalStateException("Actor token secret for kid '" + kid + "' must be at least "
                        + MIN_SECRET_CHARS + " characters");
            }
            if (parsed.put(kid, secret.getBytes(StandardCharsets.UTF_8)) != null) {
                throw new IllegalStateException("Actor token kid '" + kid + "' is listed twice");
            }
        }
        return new ActorTokenKeys(parsed);
    }

    public boolean isEmpty() {
        return secrets.isEmpty();
    }

    public int size() {
        return secrets.size();
    }

    /** The key that signs new tokens: the first one listed. */
    public String signingKid() {
        return secrets.keySet().iterator().next();
    }

    public byte[] secret(String kid) {
        byte[] secret = secrets.get(kid);
        return secret == null ? null : secret.clone();
    }

    /** The only key, when rotation is not in progress; null otherwise. */
    byte[] soleSecret() {
        return secrets.size() == 1 ? secrets.values().iterator().next().clone() : null;
    }
}
