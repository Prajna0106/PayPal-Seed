package com.paypalseed.deskquery.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The next instalment still owing, worked out from facts the desk already holds: the instalments of the current schedule
 * and the outstanding balance. Repayments settle the oldest instalment first (ADR 0006), so what has been paid is
 * the schedule total less what is outstanding, counted from the front.
 */
public record NextDue(LocalDate dueDate, long remainingCents) {

    public record Instalment(int seq, LocalDate dueDate, long amountCents) {
    }

    /** Empty when nothing is outstanding or no schedule is known. {@code instalments} must be in sequence order. */
    public static Optional<NextDue> of(List<Instalment> instalments, Long outstandingCents) {
        if (instalments.isEmpty() || (outstandingCents != null && outstandingCents <= 0)) {
            return Optional.empty();
        }
        long total = instalments.stream().mapToLong(Instalment::amountCents).sum();
        long paid = outstandingCents == null ? 0 : Math.clamp(total - outstandingCents, 0, total);
        long covered = 0;
        for (Instalment i : instalments) {
            covered += i.amountCents();
            if (covered > paid) {
                return Optional.of(new NextDue(i.dueDate(), covered - Math.max(paid, covered - i.amountCents())));
            }
        }
        return Optional.empty();
    }
}
