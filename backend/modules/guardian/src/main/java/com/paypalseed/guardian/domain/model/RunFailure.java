package com.paypalseed.guardian.domain.model;

/**
 * Why a Guardian run ended without a proposal. Hardship reads the code and keeps the template text; the
 * code is also stored on the run trace.
 */
public class RunFailure extends RuntimeException {

    public enum Code {
        AGENT_DISABLED,
        MODEL_UNAVAILABLE,
        BUDGET,
        TIMEOUT,
        SCHEMA_INVALID,
        TOOL_ERROR,
        PROPOSAL_ERROR
    }

    private final Code code;

    public RunFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public RunFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
