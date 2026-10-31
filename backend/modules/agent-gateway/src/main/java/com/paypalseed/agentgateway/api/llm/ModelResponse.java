package com.paypalseed.agentgateway.api.llm;

import java.util.List;

/** What the model answered and what it cost. {@code model} is the model that actually served the call. */
public record ModelResponse(String model, String content, List<ToolCall> toolCalls, int tokensIn, int tokensOut,
                            long costMicroUsd, long latencyMs, String finishReason) {

    public ModelResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
