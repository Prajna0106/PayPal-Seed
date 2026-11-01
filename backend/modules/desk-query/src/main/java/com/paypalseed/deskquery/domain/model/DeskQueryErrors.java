package com.paypalseed.deskquery.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Error codes of the desk read API (RFC 9457 problem types). All are caller mistakes, so all are 422. */
public final class DeskQueryErrors {

    public static final ErrorCode INVALID_FILTER =
            ErrorCode.validation("desk-query-invalid-filter", "The filter is not allowed on this list");
    public static final ErrorCode INVALID_SORT =
            ErrorCode.validation("desk-query-invalid-sort", "The sort is not allowed on this list");
    public static final ErrorCode INVALID_CURSOR =
            ErrorCode.validation("desk-query-invalid-cursor", "The page cursor is not valid for this request");
    public static final ErrorCode INVALID_LIMIT =
            ErrorCode.validation("desk-query-invalid-limit", "The page size is out of range");

    private DeskQueryErrors() {
    }
}
