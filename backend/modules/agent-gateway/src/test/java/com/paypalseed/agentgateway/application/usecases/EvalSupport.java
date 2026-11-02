package com.paypalseed.agentgateway.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Finds the datasets under the repository's {@code agents/} folder, whatever directory Gradle runs from. */
final class EvalSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private EvalSupport() {
    }

    static JsonNode load(String relativePath) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("agents"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("Cannot find the agents/ folder above " + Path.of("").toAbsolutePath());
        }
        try {
            return JSON.readTree(Files.readString(dir.resolve(relativePath)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + relativePath, e);
        }
    }

    /** The case's own {@code input}, or the referenced case's input with {@code inputPatch} laid over it. */
    static JsonNode resolveInput(JsonNode dataset, JsonNode testCase) {
        if (testCase.has("input")) {
            return testCase.get("input");
        }
        String ref = testCase.path("inputRef").asText();
        for (JsonNode other : dataset) {
            if (ref.equals(other.path("id").asText())) {
                ObjectNode merged = other.get("input").deepCopy();
                testCase.path("inputPatch").fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
                return merged;
            }
        }
        throw new IllegalStateException("Unknown inputRef " + ref);
    }

    /** The model's scripted answer as the raw text the runner would receive. */
    static String modelText(JsonNode testCase) {
        JsonNode out = testCase.get("modelOutput");
        return out.isTextual() ? out.asText() : out.toString();
    }
}
