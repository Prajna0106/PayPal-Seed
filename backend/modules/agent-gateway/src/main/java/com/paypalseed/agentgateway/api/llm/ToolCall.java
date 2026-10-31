package com.paypalseed.agentgateway.api.llm;

import java.util.Objects;

/** A tool call the model asked for. {@code argumentsJson} is untrusted model output. */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }
}
