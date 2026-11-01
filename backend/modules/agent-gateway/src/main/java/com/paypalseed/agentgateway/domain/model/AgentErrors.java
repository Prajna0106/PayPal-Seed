package com.paypalseed.agentgateway.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Error codes the agent gateway can return (RFC 9457 problem types, as in agent-tools.yaml). */
public final class AgentErrors {

    public static final ErrorCode AGENT_DISABLED = new ErrorCode("agent-disabled", ErrorCode.Kind.UNAVAILABLE,
            "Agents are switched off; the platform continues with template messages");
    public static final ErrorCode INVALID_TOKEN_REQUEST =
            ErrorCode.validation("invalid-agent-token-request", "Unknown scope or a lifetime above 10 minutes");
    public static final ErrorCode KILL_SWITCH_UP_NEEDS_ADMIN = new ErrorCode("kill-switch-up-needs-admin",
            ErrorCode.Kind.FORBIDDEN, "Only an admin may move an agent back to a less restrictive mode");

    public static final ErrorCode TOOL_CALL_LIMIT = new ErrorCode("tool-call-limit", ErrorCode.Kind.PRECONDITION_FAILED,
            "This run has used all of its tool calls");
    public static final ErrorCode INVALID_ARGUMENTS = ErrorCode.validation("invalid-arguments", "Invalid tool arguments");
    public static final ErrorCode NO_CONSENT = ErrorCode.notFound("no-consent", "No consented income data");
    public static final ErrorCode OPTION_NOT_OFFERED = ErrorCode.notFound("option-not-offered", "That option is not on the offer");
    public static final ErrorCode IDEMPOTENCY_KEY_REQUIRED =
            ErrorCode.validation("idempotency-key-required", "An Idempotency-Key header is required");

    private AgentErrors() {
    }
}
