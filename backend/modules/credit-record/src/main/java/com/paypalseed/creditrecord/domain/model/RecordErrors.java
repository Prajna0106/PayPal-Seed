package com.paypalseed.creditrecord.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Error codes the credit-record API can return (RFC 9457 problem types). */
public final class RecordErrors {

    /** The one answer for an unknown, expired, revoked or tampered token, so nothing can be learned from the difference. */
    public static final ErrorCode NOT_FOUND = ErrorCode.notFound("record-not-found", "No such record");
    /** Also returned for another member's share, so ids cannot be probed. */
    public static final ErrorCode SHARE_NOT_FOUND = ErrorCode.notFound("share-not-found", "No such share link");
    public static final ErrorCode NOTHING_TO_SHARE =
            ErrorCode.conflict("record-nothing-to-share", "There is no record to share yet");
    public static final ErrorCode SHARE_LIMIT =
            ErrorCode.conflict("share-limit-reached", "Too many active share links; revoke one first");
    public static final ErrorCode SHARE_EXPIRY_INVALID =
            ErrorCode.validation("share-expiry-invalid", "A share link lasts between 1 and 30 days");

    private RecordErrors() {
    }
}
