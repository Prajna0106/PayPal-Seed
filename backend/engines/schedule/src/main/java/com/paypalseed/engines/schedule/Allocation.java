package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of folding every money event over one schedule version. This is derived state (ADR 0004):
 * it is recomputed whenever the schedule or the event list changes, never edited.
 *
 * @param instalments       paid amount per instalment, in seq order
 * @param overpaymentCents  money held for the member beyond the outstanding balance
 * @param portionsByEvent   how each event was spread over instalments (negative = un-paid by a refund)
 */
public record Allocation(
        List<InstalmentAllocation> instalments, long overpaymentCents, Map<UUID, List<Portion>> portionsByEvent) {

    /**
     * @param paidInAdvanceCents part of {@code paidCents} that arrived as in-advance money (waterfall
     *                           step 2) rather than as money due; reschedules keep it at the tail
     */
    public record InstalmentAllocation(int seq, long amountCents, long paidCents, long paidInAdvanceCents,
                                       LocalDate fullyPaidOn) {

        public long remainingCents() {
            return amountCents - paidCents;
        }

        public boolean isFullyPaid() {
            return paidCents == amountCents;
        }
    }

    /** Part of one money event applied to one instalment. */
    public record Portion(int seq, long cents) {
    }

    public Allocation {
        instalments = List.copyOf(instalments);
        portionsByEvent = Map.copyOf(portionsByEvent);
    }

    public InstalmentAllocation of(int seq) {
        return instalments.get(seq - 1);
    }

    public long outstandingCents() {
        long sum = 0;
        for (InstalmentAllocation a : instalments) {
            sum += a.remainingCents();
        }
        return sum;
    }

    public long paidCents() {
        long sum = 0;
        for (InstalmentAllocation a : instalments) {
            sum += a.paidCents();
        }
        return sum;
    }

    public List<Portion> portionsOf(UUID eventId) {
        return portionsByEvent.getOrDefault(eventId, List.of());
    }
}
