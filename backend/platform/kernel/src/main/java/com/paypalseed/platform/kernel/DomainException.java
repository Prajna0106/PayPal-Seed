package com.paypalseed.platform.kernel;

import java.util.Map;

/** A business rule refused the request. Carries a stable {@link ErrorCode} and safe details. */
public class DomainException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public DomainException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of());
    }

    public DomainException(ErrorCode errorCode, String detail, Map<String, Object> details) {
        super(detail);
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
