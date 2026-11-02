package com.paypalseed.lending.api.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The whole current state of a loan application, published inside the transaction of every change to it:
 * submitted (with its fair check linked), first approval, second approval, rejected, payout asked of PayPal
 * ({@code DISBURSING}), payout failed (back to {@code APPROVED}), disbursed, cancelled in the cooling-off window.
 * It is a snapshot, not a delta, so a replayed or reordered delivery cannot corrupt a read model: keep the one
 * with the newest {@code changedAt}. It carries no name, email or text typed by a member or an officer.
 *
 * @param status             an {@code ApplicationStatus} name
 * @param submittedAt        never changes for an application
 * @param assignedOfficerRef pseudonymous reference of the officer who acted last (the form governance uses for
 *                           staff), never the staff id; null while no officer has acted
 * @param changedAt          when the application reached this state
 */
public record LoanApplicationChanged(UUID eventId, UUID applicationId, UUID memberId, String status,
                                     long requestedAmountCents, int termMonths, Instant submittedAt,
                                     String assignedOfficerRef, Instant changedAt) {
}
