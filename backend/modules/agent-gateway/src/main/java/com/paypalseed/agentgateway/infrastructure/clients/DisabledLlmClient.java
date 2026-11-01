package com.paypalseed.agentgateway.infrastructure.clients;

import com.paypalseed.agentgateway.api.llm.LlmClient;
import com.paypalseed.agentgateway.api.llm.LlmException;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import java.util.List;

/** Used when no API key is configured: every call fails fast and the platform runs on templates. */
final class DisabledLlmClient implements LlmClient {

    @Override
    public ModelResponse complete(ModelRequest request, List<String> models) {
        throw new LlmException(LlmException.Kind.NOT_CONFIGURED, 0, "No model API key is configured");
    }
}
