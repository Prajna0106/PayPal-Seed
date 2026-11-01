package com.paypalseed.agentgateway.api.llm;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A chat message in OpenAI-compatible shape: system, user, assistant (possibly with tool calls) or tool. */
public record ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {

    private static final Set<String> ROLES = Set.of("system", "user", "assistant", "tool");

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("unknown role " + role);
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (role.equals("tool") && toolCallId == null) {
            throw new IllegalArgumentException("a tool message answers a tool call id");
        }
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, List.of());
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, null, toolCalls);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, toolCallId, List.of());
    }

    public ChatMessage withContent(String replaced) {
        return new ChatMessage(role, replaced, toolCallId, toolCalls);
    }
}
