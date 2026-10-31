package com.paypalseed.engines.schedule;

import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * How late a loan is on a business date (03-algorithms §6).
 *
 * @param dpd                 days past due of the oldest instalment with money left to pay; due today is 0
 * @param oldestUnpaid        that instalment, if any
 * @param overdueCents        money left on instalments whose due date has passed
 * @param graceExpiredSeqs    instalments whose grace period has run out unpaid (InstalmentMissed facts)
 */
public record Aging(int dpd, Bucket bucket, boolean inGrace, Optional<Instalment> oldestUnpaid,
                    long overdueCents, List<Integer> graceExpiredSeqs) {

    public Aging {
        graceExpiredSeqs = List.copyOf(graceExpiredSeqs);
    }

    public boolean isLate() {
        return bucket.isDelinquent() && !inGrace;
    }

    public static Aging of(Schedule schedule, Allocation allocation, LocalDate asOf, RepaymentPolicy policy) {
        Optional<Instalment> oldest = Optional.empty();
        long overdue = 0;
        List<Integer> expired = new ArrayList<>();
        for (Instalment inst : schedule.instalments()) {
            InstalmentAllocation a = allocation.of(inst.seq());
            if (a.remainingCents() == 0) {
                continue;
            }
            if (oldest.isEmpty()) {
                oldest = Optional.of(inst);
            }
            long daysPast = ChronoUnit.DAYS.between(inst.dueDate(), asOf);
            if (daysPast > 0) {
                overdue += a.remainingCents();
            }
            if (daysPast > policy.graceDays()) {
                expired.add(inst.seq());
            }
        }
        int dpd = oldest.map(i -> (int) Math.max(0, ChronoUnit.DAYS.between(i.dueDate(), asOf))).orElse(0);
        boolean inGrace = dpd >= 1 && dpd <= policy.graceDays();
        return new Aging(dpd, Bucket.of(dpd), inGrace, oldest, overdue, expired);
    }
}
