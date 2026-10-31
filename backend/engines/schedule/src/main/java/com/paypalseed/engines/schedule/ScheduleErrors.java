package com.paypalseed.engines.schedule;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import java.util.Map;

/** Business-rule refusals raised by the schedule engine. Each code is part of the public API. */
public final class ScheduleErrors {

    public static final ErrorCode PRINCIPAL_NOT_WHOLE_DOLLARS =
            ErrorCode.validation("principal-not-whole-dollars", "Loan principal must be whole dollars");
    public static final ErrorCode INVALID_TERM =
            ErrorCode.validation("invalid-term", "Number of instalments is not allowed for this principal");
    public static final ErrorCode INVALID_DUE_DAY =
            ErrorCode.validation("invalid-due-day", "Due day must be between 1 and 31");
    public static final ErrorCode INSTALMENT_NOT_FOUND =
            ErrorCode.notFound("instalment-not-found", "No such instalment in the current schedule");
    public static final ErrorCode INSTALMENT_ALREADY_PAID =
            ErrorCode.validation("instalment-already-paid", "That instalment is already paid");
    public static final ErrorCode MOVE_OUT_OF_RANGE =
            ErrorCode.validation("move-days-out-of-range", "A due date can move by 1 to 14 days");
    public static final ErrorCode MOVE_CROSSES_NEXT =
            ErrorCode.validation("move-crosses-next-instalment", "The new date must stay before the next instalment");
    public static final ErrorCode MOVE_LANDS_IN_PAST =
            ErrorCode.validation("move-lands-in-past", "The new due date must be after today");
    public static final ErrorCode EXTRA_MONTHS_OUT_OF_RANGE =
            ErrorCode.validation("extra-months-out-of-range", "A plan can be extended by 1 to 6 months");
    public static final ErrorCode REAMORTIZE_NOT_BENEFICIAL =
            ErrorCode.validation("reamortize-not-beneficial", "A longer plan would not lower the instalment");
    public static final ErrorCode NOTHING_OUTSTANDING =
            ErrorCode.validation("nothing-outstanding", "The loan has nothing left to pay");

    private ScheduleErrors() {
    }

    static DomainException violation(ErrorCode code, String detail) {
        return new DomainException(code, detail);
    }

    static DomainException violation(ErrorCode code, String detail, Map<String, Object> details) {
        return new DomainException(code, detail, details);
    }
}
