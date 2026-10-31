package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Desired-state reconciliation of a PayPal autopay subscription (03-algorithms §9, ADR 0005).
 *
 * <p>The schedule is the truth; the subscription is an actuator. {@link #desired} says what the
 * subscription should look like now, {@link #plan} lists the fewest commands to get there. Running
 * the plan and planning again yields nothing (convergence), so lost webhooks and timeouts heal on
 * the next pass.
 */
public final class MandateReconciliation {

    public enum MandateStatus {
        APPROVAL_PENDING,
        ACTIVE,
        SUSPENDED,
        CANCELLED,
        EXPIRED;

        public boolean isLive() {
            return this == ACTIVE || this == SUSPENDED;
        }
    }

    /** What PayPal reports. {@code nextBillingDate} is in the program time zone. */
    public record Observed(MandateStatus status, long quantity, Optional<LocalDate> nextBillingDate) {
        public Observed {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(nextBillingDate, "nextBillingDate");
        }
    }

    public record DueAmount(LocalDate dueDate, long remainingCents) {
        public DueAmount {
            if (remainingCents <= 0 || remainingCents % 100 != 0) {
                throw new IllegalArgumentException("autopay collects whole-dollar amounts only");
            }
        }
    }

    /**
     * What the schedule says can be collected: {@code collectible} is false when the loan is not
     * active, not on autopay, or fully paid.
     */
    public record CollectionSpec(boolean collectible, List<DueAmount> unpaid) {
        public CollectionSpec {
            unpaid = unpaid.stream().sorted(Comparator.comparing(DueAmount::dueDate)).toList();
        }
    }

    public enum State {
        ACTIVE,
        SUSPENDED,
        CANCELLED
    }

    public enum Reason {
        COLLECT_INSTALMENT,
        NO_INSTALMENT_THIS_CYCLE,
        NOTHING_UPCOMING,
        NOT_COLLECTIBLE
    }

    /**
     * @param quantity  dollars to charge on the next cycle (only meaningful when ACTIVE)
     * @param drift     an upcoming instalment falls between today and PayPal's next billing date and
     *                  will not be collected by autopay; the caller sends a Payment Link instead
     */
    public record Desired(State state, long quantity, Reason reason, Optional<LocalDate> targetDue, boolean drift) {
    }

    public sealed interface Command permits Cancel, Suspend, Activate, Revise {
    }

    public record Cancel() implements Command {
    }

    public record Suspend() implements Command {
    }

    public record Activate() implements Command {
    }

    public record Revise(long quantity) implements Command {
    }

    private MandateReconciliation() {
    }

    public static Desired desired(CollectionSpec spec, Observed observed, LocalDate asOf, RepaymentPolicy policy) {
        if (!spec.collectible()) {
            return new Desired(State.CANCELLED, 0, Reason.NOT_COLLECTIBLE, Optional.empty(), false);
        }
        if (spec.unpaid().isEmpty()) {
            return new Desired(State.CANCELLED, 0, Reason.NOTHING_UPCOMING, Optional.empty(), false);
        }
        Optional<LocalDate> nextBilling = observed.nextBillingDate().filter(d -> !d.isBefore(asOf));
        if (nextBilling.isEmpty()) {
            // Unknown or stale billing date (e.g. suspended): aim at the next instalment not yet due.
            return spec.unpaid().stream()
                    .filter(d -> !d.dueDate().isBefore(asOf))
                    .findFirst()
                    .map(d -> collect(d))
                    .orElseGet(() -> new Desired(State.SUSPENDED, 0, Reason.NOTHING_UPCOMING, Optional.empty(), false));
        }
        LocalDate nb = nextBilling.get();
        Optional<DueAmount> target = spec.unpaid().stream()
                .filter(d -> Math.abs(ChronoUnit.DAYS.between(d.dueDate(), nb)) <= policy.mandateDateToleranceDays())
                .min(Comparator.comparingLong(d -> Math.abs(ChronoUnit.DAYS.between(d.dueDate(), nb))));
        if (target.isPresent()) {
            return collect(target.get());
        }
        boolean drift = spec.unpaid().stream().anyMatch(d -> !d.dueDate().isBefore(asOf)
                && d.dueDate().isBefore(nb.minusDays(policy.mandateDateToleranceDays())));
        return new Desired(State.SUSPENDED, 0, Reason.NO_INSTALMENT_THIS_CYCLE, Optional.empty(), drift);
    }

    public static List<Command> plan(Desired desired, Observed observed) {
        List<Command> commands = new ArrayList<>();
        if (!observed.status().isLive()) {
            return commands; // nothing to steer until the member approves; ended mandates stay ended
        }
        switch (desired.state()) {
            case CANCELLED -> commands.add(new Cancel());
            case SUSPENDED -> {
                if (observed.status() == MandateStatus.ACTIVE) {
                    commands.add(new Suspend());
                }
            }
            case ACTIVE -> {
                if (observed.quantity() != desired.quantity()) {
                    commands.add(new Revise(desired.quantity())); // revise first: activation then charges the right amount
                }
                if (observed.status() == MandateStatus.SUSPENDED) {
                    commands.add(new Activate());
                }
            }
        }
        return commands;
    }

    /** The state PayPal ends up in after {@code commands} succeed. Used by tests and by the executor's local model. */
    public static Observed apply(Observed observed, List<Command> commands) {
        Observed result = observed;
        for (Command c : commands) {
            result = switch (c) {
                case Cancel x -> new Observed(MandateStatus.CANCELLED, result.quantity(), Optional.empty());
                case Suspend x -> new Observed(MandateStatus.SUSPENDED, result.quantity(), result.nextBillingDate());
                case Activate x -> new Observed(MandateStatus.ACTIVE, result.quantity(), result.nextBillingDate());
                case Revise r -> new Observed(result.status(), r.quantity(), result.nextBillingDate());
            };
        }
        return result;
    }

    private static Desired collect(DueAmount due) {
        return new Desired(State.ACTIVE, due.remainingCents() / 100, Reason.COLLECT_INSTALMENT,
                Optional.of(due.dueDate()), false);
    }
}
