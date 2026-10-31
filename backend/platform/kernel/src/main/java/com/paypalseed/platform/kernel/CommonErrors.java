package com.paypalseed.platform.kernel;

/** Error codes shared by every module. Module-specific codes live next to their module. */
public final class CommonErrors {

    public static final ErrorCode NOT_FOUND = ErrorCode.notFound("not-found", "Resource not found");
    public static final ErrorCode UNAUTHENTICATED =
            new ErrorCode("unauthenticated", ErrorCode.Kind.UNAUTHENTICATED, "Authentication required");
    public static final ErrorCode FORBIDDEN = new ErrorCode("forbidden", ErrorCode.Kind.FORBIDDEN, "Not allowed");
    public static final ErrorCode IDEMPOTENCY_KEY_REUSED =
            ErrorCode.conflict("idempotency-key-reused", "Idempotency key was used with a different request");
    public static final ErrorCode DEPENDENCY_UNAVAILABLE = new ErrorCode(
            "dependency-unavailable", ErrorCode.Kind.UNAVAILABLE, "A dependency is unavailable; retry later");

    private CommonErrors() {
    }
}
