package com.paypalseed.governance.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Error codes the governance API can return (RFC 9457 problem types). */
public final class GovernanceErrors {

    public static final ErrorCode PROPOSAL_KIND_UNKNOWN =
            ErrorCode.validation("proposal-kind-unknown", "No validator is registered for this proposal kind");
    public static final ErrorCode PROPOSAL_PAYLOAD_INVALID =
            ErrorCode.validation("proposal-payload-invalid", "The proposal payload is not a JSON object");
    public static final ErrorCode APPROVAL_REQUEST_NOT_FOUND =
            ErrorCode.notFound("approval-request-not-found", "No such approval request");
    public static final ErrorCode APPROVAL_REQUEST_CLOSED =
            ErrorCode.conflict("approval-request-closed", "The approval request is already decided or withdrawn");
    public static final ErrorCode APPROVAL_REQUEST_OPEN =
            ErrorCode.conflict("approval-request-open", "Another request for this subject is still open");
    public static final ErrorCode SOD_VIOLATION = new ErrorCode("sod-violation", ErrorCode.Kind.FORBIDDEN,
            "Separation of duties: the checker must differ from the maker, and neither may be the member");
    public static final ErrorCode DECIDER_ROLE_REQUIRED = new ErrorCode("decider-role-required", ErrorCode.Kind.FORBIDDEN,
            "The maker needs the OFFICER role and the checker the CHECKER role");
    public static final ErrorCode REASON_CODE_REQUIRED =
            ErrorCode.validation("reason-code-required", "Every decision needs a reason code");

    private GovernanceErrors() {
    }
}
