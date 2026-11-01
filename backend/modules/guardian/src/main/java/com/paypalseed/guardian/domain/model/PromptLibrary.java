package com.paypalseed.guardian.domain.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The versioned Guardian prompts, loaded from the classpath. Their hash, together with the output schemas,
 * is recorded on every run, so any change to what the model is told shows up in the run trace.
 */
public final class PromptLibrary {

    public static final String VERSION = "v1";

    private final String gather;
    private final String secondOpinion;
    private final String narrative;
    private final String hash;

    private PromptLibrary(String gather, String secondOpinion, String narrative) {
        this.gather = gather;
        this.secondOpinion = secondOpinion;
        this.narrative = narrative;
        this.hash = "sha256:" + sha256Hex(String.join("\u0000", VERSION, gather, secondOpinion, narrative,
                Schemas.SECOND_OPINION, Schemas.NARRATIVE)).substring(0, 16);
    }

    public static PromptLibrary load() {
        return new PromptLibrary(read("gather.md"), read("second-opinion.md"), read("narrative.md"));
    }

    public String gather(int remainingCalls) {
        return gather.replace("{{remainingCalls}}", Integer.toString(remainingCalls));
    }

    public String secondOpinion() {
        return secondOpinion;
    }

    public String narrative() {
        return narrative;
    }

    /** {@code sha256:} plus 16 hex characters, the format of {@code promptHash} in the proposal contract. */
    public String hash() {
        return hash;
    }

    private static String read(String name) {
        String path = "/guardian/prompts/" + VERSION + "/" + name;
        try (InputStream in = PromptLibrary.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
