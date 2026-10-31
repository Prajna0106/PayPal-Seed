package com.paypalseed.payments.application.ports;

/** A PayPal call failed. {@link Kind} decides what the executor does next. */
public class PayPalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 429 / 5xx: safe to retry with the same request id. */
        RETRYABLE,
        /** Timeout or reset: PayPal may or may not have acted; re-observe or replay the same request id. */
        AMBIGUOUS,
        /** 404 / 422: our picture of the PayPal object is stale; re-observe and re-plan. */
        STATE,
        /** Other 4xx: the request is wrong; retrying cannot help. */
        FINAL,
        /** 401 after a token refresh: credentials are wrong. */
        AUTH
    }

    private final Kind kind;
    private final int httpStatus;
    private final String paypalIssue;

    public PayPalException(Kind kind, int httpStatus, String paypalIssue, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.paypalIssue = paypalIssue;
    }

    public PayPalException(Kind kind, int httpStatus, String paypalIssue, String message) {
        this(kind, httpStatus, paypalIssue, message, null);
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String paypalIssue() {
        return paypalIssue;
    }

    public boolean isTransient() {
        return kind == Kind.RETRYABLE || kind == Kind.AMBIGUOUS;
    }

    public static Kind classify(int status) {
        if (status == 429 || status >= 500) {
            return Kind.RETRYABLE;
        }
        if (status == 401) {
            return Kind.AUTH;
        }
        if (status == 404 || status == 409 || status == 422) {
            return Kind.STATE;
        }
        return Kind.FINAL;
    }
}
