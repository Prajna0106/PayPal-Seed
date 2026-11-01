package com.paypalseed.agentgateway.api.llm;

/** A model call failed. {@link #retryable()} drives the gateway's single retry and the circuit breakers. */
public class LlmException extends RuntimeException {

    public enum Kind {
        /** 408, 429, 5xx, timeouts and connection errors. */
        RETRYABLE,
        /** 4xx such as a bad request, an invalid key or no credits. */
        PERMANENT,
        /** No API key is configured: not a provider failure, so it never opens a circuit. */
        NOT_CONFIGURED
    }

    private final Kind kind;
    private final int status;

    public LlmException(Kind kind, int status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public LlmException(Kind kind, int status, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return kind == Kind.RETRYABLE;
    }
}
