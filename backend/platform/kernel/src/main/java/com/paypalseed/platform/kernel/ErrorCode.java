package com.paypalseed.platform.kernel;

/**
 * A stable, machine-readable error. {@code code} becomes the RFC 9457 problem {@code type} suffix,
 * so clients and Postman tests can branch on it. The web layer maps {@link Kind} to an HTTP status.
 */
public record ErrorCode(String code, Kind kind, String title) {

    public enum Kind {
        VALIDATION,
        NOT_FOUND,
        CONFLICT,
        PRECONDITION_FAILED,
        UNAUTHENTICATED,
        FORBIDDEN,
        UNAVAILABLE
    }

    public static ErrorCode validation(String code, String title) {
        return new ErrorCode(code, Kind.VALIDATION, title);
    }

    public static ErrorCode notFound(String code, String title) {
        return new ErrorCode(code, Kind.NOT_FOUND, title);
    }

    public static ErrorCode conflict(String code, String title) {
        return new ErrorCode(code, Kind.CONFLICT, title);
    }
}
