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

    /**
     * Money went back to the fund after it was paid out (ADR 0024): {@code UNUSED_COVER} when a cover credited a loan
     * for more than its schedule took (the member paid in the same moment; reserve posted the correction,
     * {@code journalEntryId}), {@code LOAN_VOIDED} when the loan was voided and its cover returned in lending's entry.
     * {@code returnRef} is the idempotency key. No cash moved in either case.
     */
    public record CoverReturned(UUID eventId, String returnRef, String kind, UUID loanId, long amountCents,
                                UUID journalEntryId, String reasonCode, LocalDate businessDate) {
    }

    public record CoverRejected(UUID eventId, UUID coverRequestId, UUID loanId, UUID memberId, String reason,
                                LocalDate businessDate) {
    }
}
