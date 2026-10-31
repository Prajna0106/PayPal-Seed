package com.paypalseed.governance.api;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import java.util.Map;

/** What the member saw is no longer what would happen: the request was voided and must be reopened. */
public class ApprovalStaleException extends DomainException {

    public static final ErrorCode APPROVAL_STALE =
            ErrorCode.conflict("approval-stale", "The approved snapshot no longer matches; the request was voided");

    public ApprovalStaleException(String detail, Map<String, Object> details) {
        super(APPROVAL_STALE, detail, details);
    }
}
