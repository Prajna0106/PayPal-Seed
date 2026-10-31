package com.paypalseed.engines.schedule;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable version of a loan's repayment plan (ADR 0004). The constructor enforces INV-1:
 * instalments are numbered 1..n, due dates never go backwards, and amounts sum to the principal.
 */
public record Schedule(int version, ScheduleReason reason, long principalCents, int dueDay, List<Instalment> instalments) {

    public enum ScheduleReason {
        ORIGINAL,
        HARDSHIP_MOVE_DATE,
        HARDSHIP_PAUSE,
        HARDSHIP_REAMORTIZE,
        CORRECTION
    }

    public Schedule {
        Objects.requireNonNull(reason, "reason");
        instalments = List.copyOf(instalments);
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1");
        }
        if (dueDay < 1 || dueDay > 31) {
            throw new IllegalArgumentException("dueDay must be 1..31");
        }
        if (instalments.isEmpty()) {
            throw new IllegalArgumentException("a schedule needs at least one instalment");
        }
        long sum = 0;
        for (int i = 0; i < instalments.size(); i++) {
            Instalment inst = instalments.get(i);
            if (inst.seq() != i + 1) {
                throw new IllegalArgumentException("instalments must be numbered 1..n in order");
            }
            if (i > 0 && inst.dueDate().isBefore(instalments.get(i - 1).dueDate())) {
                throw new IllegalArgumentException("due dates must not go backwards");
            }
            sum = Math.addExact(sum, inst.amountCents());
        }
        if (sum != principalCents) {
            throw new IllegalArgumentException("instalments sum to " + sum + " but principal is " + principalCents);
        }
    }

    public int size() {
        return instalments.size();
    }

    public Instalment instalment(int seq) {
        return find(seq).orElseThrow(() -> ScheduleErrors.violation(
                ScheduleErrors.INSTALMENT_NOT_FOUND, "Instalment " + seq + " is not in version " + version));
    }

    public Optional<Instalment> find(int seq) {
        return seq >= 1 && seq <= instalments.size() ? Optional.of(instalments.get(seq - 1)) : Optional.empty();
    }

    public Instalment last() {
        return instalments.get(instalments.size() - 1);
    }
}
