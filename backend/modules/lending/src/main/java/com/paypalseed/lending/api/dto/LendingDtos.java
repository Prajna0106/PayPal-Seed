package com.paypalseed.lending.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LendingDtos {

    private LendingDtos() {
    }

    /** F2 calls this once the disbursement payout has succeeded. Idempotent on {@code disbursementRef}. */
    public record RegisterDisbursedLoan(UUID memberId, long principalCents, int instalmentCount, int dueDay,
                                        LocalDate disbursedOn, String disbursementRef) {
        public RegisterDisbursedLoan {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(disbursedOn, "disbursedOn");
            Objects.requireNonNull(disbursementRef, "disbursementRef");
        }
    }

    public enum ReschedulePrimitive {
        MOVE_DATE,
        DEFER_TO_END,
        REAMORTIZE
    }

    /** F5 hardship options A, B and C. {@code days} for MOVE_DATE, {@code extraMonths} for REAMORTIZE. */
    public record RescheduleRequest(UUID loanId, ReschedulePrimitive primitive, Integer seq, Integer days,
                                    Integer extraMonths) {
        public RescheduleRequest {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(primitive, "primitive");
        }
    }

    public record InstalmentLine(int seq, LocalDate dueDate, long amountCents, long paidCents, long remainingCents,
                                 String status, LocalDate paidOn) {
    }

    public record ScheduleView(UUID loanId, int version, String reason, LocalDate businessDate, long principalCents,
                               long outstandingCents, long overpaidCents, String loanStatus, String collectionMode,
                               int dpd, String bucket, List<InstalmentLine> instalments) {
    }

    public record Slot(int seq, LocalDate dueDate, long amountCents) {
    }

    public record ScheduleDiffView(List<Slot> removed, List<Slot> added) {
    }

    public record VersionView(int version, String reason, String causedBy, LocalDate createdOn, List<Slot> instalments,
                              Optional<ScheduleDiffView> diffFromPrevious) {
    }

    public record ReschedulePreview(ScheduleView current, ScheduleView proposed, ScheduleDiffView diff) {
    }

    public record LoanSummary(UUID loanId, UUID memberId, long principalCents, long outstandingCents, String status,
                              String collectionMode, String bucket, int dpd, int currentVersion) {
    }
}
