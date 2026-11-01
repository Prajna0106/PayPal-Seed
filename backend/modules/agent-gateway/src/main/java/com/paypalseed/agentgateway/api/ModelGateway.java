package com.paypalseed.agentgateway.api;

import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;

/**
 * The only way another module reaches a model. Every call passes the kill switch, the daily budget, a
 * bulkhead, per-model circuit breakers, PII redaction and one retry. Callers must treat
 * {@link ModelUnavailableException} as normal and fall back to deterministic templates.
 */
public interface ModelGateway {

    ModelResponse complete(ModelRequest request);
}
