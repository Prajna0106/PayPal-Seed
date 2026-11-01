package com.paypalseed.fraud.domain.model;

import com.paypalseed.platform.kernel.ErrorCode;

/** Stable error codes of the fraud module; each becomes a problem {@code type}. */
public final class FraudErrors {

    public static final ErrorCode FLAG_NOT_FOUND = ErrorCode.notFound("fraud-flag-not-found", "No such fraud flag");
    public static final ErrorCode FLAG_ALREADY_REVIEWED =
            ErrorCode.conflict("fraud-flag-already-reviewed", "This flag was already reviewed");
    public static final ErrorCode NOTE_REQUIRED =
            ErrorCode.validation("fraud-note-required", "Confirming a flag needs a note that says why");

    private FraudErrors() {
    }
}
