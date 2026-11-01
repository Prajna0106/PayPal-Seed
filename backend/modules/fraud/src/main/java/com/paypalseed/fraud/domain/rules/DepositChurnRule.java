package com.paypalseed.fraud.domain.rules;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Fact;
import com.paypalseed.fraud.domain.model.Signal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Money that comes in and goes straight back out. A withdrawal counts as churn when at least 80% of it was deposited in
 * the 48 hours before and it is at least $25. Deposits are matched oldest first and used once, so one deposit cannot
 * explain two withdrawals. One such round trip is a small signal; a repeat is a larger one.
 */
public final class DepositChurnRule implements Rule {

    public static final String CODE = "DEPOSIT_CHURN";
    static final Duration WINDOW = Duration.ofHours(48);
    static final long MIN_WITHDRAWAL_CENTS = 2_500;
    static final int MIN_RECENT_PERCENT = 80;
    static final int WEIGHT_ONCE = 15;
    static final int WEIGHT_REPEATED = 35;

    private static final class Lot {
        final Fact deposit;
        long remaining;

        Lot(Fact deposit) {
            this.deposit = deposit;
            this.remaining = deposit.amountOrZero();
        }
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<Signal> evaluate(RuleContext context) {
        Deque<Lot> lots = new ArrayDeque<>();
        Set<Long> used = new LinkedHashSet<>();
        int roundTrips = 0;
        long withdrawn = 0;
        long matchedTotal = 0;
        for (Fact fact : context.of(FactKind.DEPOSIT, FactKind.WITHDRAWAL)) {
            if (fact.kind() == FactKind.DEPOSIT) {
                if (fact.amountOrZero() > 0) {
                    lots.addLast(new Lot(fact));
                }
                continue;
            }
            while (!lots.isEmpty() && Duration.between(lots.peekFirst().deposit.occurredAt(), fact.occurredAt()).compareTo(WINDOW) > 0) {
                lots.removeFirst();
            }
            long amount = fact.amountOrZero();
            long recent = lots.stream().mapToLong(l -> l.remaining).sum();
            long matched = Math.min(amount, recent);
            boolean churn = amount >= MIN_WITHDRAWAL_CENTS && matched * 100 >= amount * MIN_RECENT_PERCENT;
            List<Long> drawn = consume(lots, matched);
            if (churn) {
                roundTrips++;
                withdrawn += amount;
                matchedTotal += matched;
                used.addAll(drawn);
                used.add(fact.id());
            }
        }
        if (roundTrips == 0) {
            return Optional.empty();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("roundTrips", roundTrips);
        evidence.put("withdrawnCents", withdrawn);
        evidence.put("depositedWithinWindowCents", matchedTotal);
        evidence.put("windowHours", WINDOW.toHours());
        evidence.put("minRecentPercent", MIN_RECENT_PERCENT);
        int weight = roundTrips >= 2 ? WEIGHT_REPEATED : WEIGHT_ONCE;
        return Optional.of(new Signal(CODE, weight,
                roundTrips == 1 ? "Money was withdrawn within 48 hours of arriving, once"
                        : "Money was withdrawn within 48 hours of arriving, " + roundTrips + " times",
                evidence, new ArrayList<>(used), context.asOf()));
    }

    /** Takes {@code cents} from the oldest lots and returns the deposit ids it touched. */
    private static List<Long> consume(Deque<Lot> lots, long cents) {
        List<Long> touched = new ArrayList<>();
        long left = cents;
        while (left > 0 && !lots.isEmpty()) {
            Lot lot = lots.peekFirst();
            long take = Math.min(left, lot.remaining);
            lot.remaining -= take;
            left -= take;
            touched.add(lot.deposit.id());
            if (lot.remaining == 0) {
                lots.removeFirst();
            }
        }
        return touched;
    }
}
