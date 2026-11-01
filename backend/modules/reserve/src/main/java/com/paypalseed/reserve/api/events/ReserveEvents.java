package com.paypalseed.reserve.api.events;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Domain events published by reserve, inside the transaction that changed the fund. Delivery is at least once:
 * consumers dedupe on {@code eventId}. None carries a donor's identity.
 */
public final class ReserveEvents {

    private ReserveEvents() {
    }

    public record DonationReceived(UUID eventId, UUID donationId, String captureId, long amountCents,
                                   LocalDate businessDate) {
    }

    public record CoverRequested(UUID eventId, UUID coverRequestId, UUID memberId, UUID loanId, long amountCents,
                                 LocalDate businessDate) {
    }

    /**
     * The fund paid {@code amountCents} of the loan. The ledger entry ({@code journalEntryId}) already credits the
     * loan's receivable, so lending applies the amount to the schedule as a repayment from the reserve and must not
     * post the money to the ledger a second time. {@code approvalRef} is the idempotency key for that application.
     */
    public record CoverApproved(UUID eventId, UUID coverRequestId, UUID loanId, UUID memberId, long amountCents,
                                UUID journalEntryId, String approvalRef, LocalDate businessDate) {
    }

    public record CoverRejected(UUID eventId, UUID coverRequestId, UUID loanId, UUID memberId, String reason,
                                LocalDate businessDate) {
    }
}
