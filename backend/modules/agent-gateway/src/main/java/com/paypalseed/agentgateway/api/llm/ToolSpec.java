package com.paypalseed.agentgateway.api.llm;

import java.util.Objects;

/** A function the model may call; {@code parametersJsonSchema} is JSON Schema text. */
public record ToolSpec(String name, String description, String parametersJsonSchema) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJsonSchema, "parametersJsonSchema");
    }
}
