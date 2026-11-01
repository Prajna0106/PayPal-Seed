package com.paypalseed.agentgateway.api.llm;

import java.util.List;

/**
 * The model provider port. {@code models} is the ordered fallback chain the gateway allows for this call
 * (circuits already filtered). Implementations never log message content and throw {@link LlmException}
 * on any failure. Callers outside this module use {@code ModelGateway}, which adds the guard rails.
 */
public interface LlmClient {

    ModelResponse complete(ModelRequest request, List<String> models);
}
