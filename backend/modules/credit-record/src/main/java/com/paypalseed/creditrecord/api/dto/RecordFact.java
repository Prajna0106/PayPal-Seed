package com.paypalseed.creditrecord.api.dto;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One fact about a member's saving or repaying, as the wiring module hands it to the record. {@code factKey} is the
 * business key: the same key is recorded once however many times it is delivered. A fact holds no money amounts and
 * no personal data.
 */
public record RecordFact(String factKey, UUID memberId, Kind kind, LocalDate occurredOn, UUID loanId, Integer seq,
                         Integer daysLate, Integer missedCount, UUID caseId) {

    public enum Kind {
        SAVINGS_DEPOSIT,
        /** The calendar months before {@code occurredOn}'s month are over: a month with no deposit was missed. */
        SAVING_MONTHS_CLOSED,
        LOAN_DISBURSED,
        INSTALMENT_PAID,
        /** The grace period ran out with the instalment unpaid. */
        INSTALMENT_MISSED,
        LOAN_REPAID,
        /** The only fact that lowers a level. */
        LOAN_DEFAULTED,
        /** An approved hardship plan is in force. Counts as on plan; never a miss (ADR 0011). */
        HARDSHIP_PLAN_APPLIED
    }

    public RecordFact {
        Objects.requireNonNull(factKey, "factKey");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredOn, "occurredOn");
        if (factKey.isBlank() || factKey.length() > 200) {
            throw new IllegalArgumentException("factKey must be 1 to 200 characters");
        }
        switch (kind) {
            case INSTALMENT_PAID -> {
                require(loanId, seq, daysLate);
                if (daysLate < 0) {
                    throw new IllegalArgumentException("daysLate cannot be negative; early payments are 0");
                }
            }
            case INSTALMENT_MISSED -> require(loanId, seq);
            case LOAN_REPAID -> {
                require(loanId, missedCount);
                if (missedCount < 0) {
                    throw new IllegalArgumentException("missedCount cannot be negative");
                }
            }
            case LOAN_DISBURSED, LOAN_DEFAULTED -> require(loanId);
            case HARDSHIP_PLAN_APPLIED -> require(caseId);
            case SAVINGS_DEPOSIT, SAVING_MONTHS_CLOSED -> {
            }
        }
        if (seq != null && seq < 1) {
            throw new IllegalArgumentException("seq starts at 1");
        }
    }

    private static void require(Object... needed) {
        for (Object o : needed) {
            Objects.requireNonNull(o, "a field this kind of fact needs is missing");
        }
    }

    public static RecordFact savingsDeposit(String factKey, UUID memberId, LocalDate on) {
        return new RecordFact(factKey, memberId, Kind.SAVINGS_DEPOSIT, on, null, null, null, null, null);
    }

    public static RecordFact savingMonthsClosed(UUID memberId, LocalDate asOf) {
        return new RecordFact("saving-close:" + memberId + ":" + asOf.getYear() + "-" + asOf.getMonthValue(), memberId,
                Kind.SAVING_MONTHS_CLOSED, asOf, null, null, null, null, null);
    }

    public static RecordFact loanDisbursed(String factKey, UUID memberId, UUID loanId, LocalDate on) {
        return new RecordFact(factKey, memberId, Kind.LOAN_DISBURSED, on, loanId, null, null, null, null);
    }

    /** Keyed by loan and instalment: an instalment is paid once, whichever event reports it. */
    public static RecordFact instalmentPaid(UUID memberId, UUID loanId, int seq, int daysLate, LocalDate paidOn) {
        return new RecordFact("inst-paid:" + loanId + ":" + seq, memberId, Kind.INSTALMENT_PAID, paidOn, loanId, seq,
                daysLate, null, null);
    }

    public static RecordFact instalmentMissed(String factKey, UUID memberId, UUID loanId, int seq, LocalDate on) {
        return new RecordFact(factKey, memberId, Kind.INSTALMENT_MISSED, on, loanId, seq, null, null, null);
    }

    /** Keyed by loan: reopening and repaying a loan again does not count it twice. */
    public static RecordFact loanRepaid(UUID memberId, UUID loanId, int missedCount, LocalDate on) {
        return new RecordFact("loan-repaid:" + loanId, memberId, Kind.LOAN_REPAID, on, loanId, null, null, missedCount,
                null);
    }

    /** Keyed by loan: one default per loan, however many times it ages past the line. */
    public static RecordFact loanDefaulted(UUID memberId, UUID loanId, LocalDate on) {
        return new RecordFact("loan-default:" + loanId, memberId, Kind.LOAN_DEFAULTED, on, loanId, null, null, null,
                null);
    }

    public static RecordFact hardshipPlanApplied(String factKey, UUID memberId, UUID caseId, UUID loanId, LocalDate on) {
        return new RecordFact(factKey, memberId, Kind.HARDSHIP_PLAN_APPLIED, on, loanId, null, null, null, caseId);
    }
}
