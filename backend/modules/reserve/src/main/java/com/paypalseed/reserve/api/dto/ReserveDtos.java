package com.paypalseed.reserve.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ReserveDtos {

    private ReserveDtos() {
    }

    public enum CoverStatus {
        PENDING_APPROVAL,
        APPROVED,
        REJECTED,
        /** Governance approved but the fund or the loan could no longer take the cover; nothing was posted. */
        FAILED
    }

    /** {@code replay} is true when the capture had been recorded before. */
    public record DonationReceipt(UUID donationId, String captureId, long amountCents, boolean replay) {
    }

    /**
     * Money that went back to the fund from an approved cover. {@code replay} is true when this approval reference had
     * been returned before (nothing changed). {@code journalEntryId} is the entry reserve posted.
     */
    public record CoverReturn(String returnRef, UUID loanId, long amountCents, UUID journalEntryId, boolean replay) {
    }

    /** What a donor asked for before PayPal: the custom id travels on the order and comes back on the capture. */
    public record DonationIntent(UUID intentId, String customId) {
    }

    /**
     * The public face of the fund. {@code coverageRatioBps} is the balance over the open exposure in basis points,
     * null when nothing is owed. {@code supporters} holds display names of donors who opted in, nothing else.
     */
    public record ReserveSummary(long balanceCents, long totalDonatedCents, int donationCount, long totalPaidOutCents,
                                 int membersCovered, long openExposureCents, Long coverageRatioBps,
                                 List<String> supporters, LocalDate asOf) {
        public ReserveSummary {
            supporters = List.copyOf(supporters);
        }
    }

    public record CreateCover(UUID memberId, UUID loanId, long amountCents, String reasonCode, UUID hardshipCaseId,
                              String note) {
        public CreateCover {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(reasonCode, "reasonCode");
        }
    }

    /** One signature on a cover request: {@code role} is MAKER or CHECKER, {@code staffId} the officer. No free text. */
    public record CoverApproval(String role, UUID staffId, Instant decidedAt) {
    }

    /**
     * {@code slaDueAt} is when the officers' decision is overdue (the time of the request plus the decision SLA), null
     * if the approval request cannot be read. {@code approvals} lists who has signed so far, oldest first.
     */
    public record CoverRequestView(UUID id, UUID memberId, UUID loanId, long amountCents, String reasonCode,
                                   UUID hardshipCaseId, CoverStatus status, UUID approvalRequestId, LocalDate requestedOn,
                                   String closedReason, UUID journalEntryId, Instant slaDueAt, List<CoverApproval> approvals) {
        public CoverRequestView {
            approvals = List.copyOf(approvals);
        }
    }

    public record CoverRequestPage(List<CoverRequestView> rows, UUID nextBefore) {
        public CoverRequestPage {
            rows = List.copyOf(rows);
        }
    }

    public record CoverDecisionResult(UUID requestId, String requestStatus, String role, String next, long auditSeq,
                                      CoverStatus coverStatus) {
    }
}
