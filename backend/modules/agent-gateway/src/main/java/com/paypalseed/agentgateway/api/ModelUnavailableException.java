package com.paypalseed.agentgateway.api;

/** No model answer is available for this call. The safety net continues with templates. */
public class ModelUnavailableException extends RuntimeException {

    public enum Reason {
        /** Kill switch in {@code TEMPLATES_ONLY}. */
        DISABLED,
        /** No API key configured, or {@code seed.llm.enabled=false}. */
        NOT_CONFIGURED,
        BUDGET_EXHAUSTED,
        /** Every model in the chain has an open circuit. */
        CIRCUIT_OPEN,
        /** Too many concurrent model calls in this process. */
        BULKHEAD_FULL,
        /** The provider failed after the retry. */
        FAILED
    }

    private final Reason reason;

    public ModelUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ModelUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
