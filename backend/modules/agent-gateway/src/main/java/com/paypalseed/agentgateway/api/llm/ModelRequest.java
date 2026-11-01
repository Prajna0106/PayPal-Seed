package com.paypalseed.agentgateway.api.llm;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One model call. {@code jsonSchema} (JSON text) asks for strict structured output; {@code tools} may be
 * empty. Timeouts and token caps are enforced by the gateway and the client, not by the prompt.
 */
public record ModelRequest(String agent, UUID runId, String purpose, List<ChatMessage> messages, List<ToolSpec> tools,
                           String jsonSchemaName, String jsonSchema, int maxOutputTokens, Duration timeout) {

    public ModelRequest {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(purpose, "purpose");
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("a model request needs at least one message");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 4096) {
            throw new IllegalArgumentException("maxOutputTokens must be 1..4096");
        }
        if ((jsonSchemaName == null) != (jsonSchema == null)) {
            throw new IllegalArgumentException("jsonSchemaName and jsonSchema go together");
        }
        Objects.requireNonNull(timeout, "timeout");
    }

    /** The same request with other messages, for example after redaction. */
    public ModelRequest withMessages(List<ChatMessage> replaced) {
        return new ModelRequest(agent, runId, purpose, replaced, tools, jsonSchemaName, jsonSchema, maxOutputTokens, timeout);
    }

    public ModelRequest withTimeout(Duration replaced) {
        return new ModelRequest(agent, runId, purpose, messages, tools, jsonSchemaName, jsonSchema, maxOutputTokens, replaced);
    }
}
