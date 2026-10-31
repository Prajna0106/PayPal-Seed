package com.paypalseed.engines.schedule;

import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.Allocation.Portion;
import com.paypalseed.engines.schedule.MoneyEvent.OverpaymentRefund;
import com.paypalseed.engines.schedule.MoneyEvent.Receipt;
import com.paypalseed.engines.schedule.MoneyEvent.Reversal;
import com.paypalseed.engines.schedule.RepaymentPolicy.FutureInstalmentRule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The repayment allocation waterfall (03-algorithms §5, ADR 0006), as a pure fold.
 *
 * <p>Like Fineract's transaction reprocessing, every allocation is recomputed from the full,
 * ordered list of money events. A backdated payment, a reversal or a new schedule version is
 * handled by running the fold again, so the result never depends on the order in which webhooks
 * arrived.
 *
 * <ol>
 *   <li>Due money: instalments due on or before {@code date + dueWindowDays}, oldest first
 *       (Fineract's past-due, then due).</li>
 *   <li>In-advance money: by {@link FutureInstalmentRule}; the default pays from the last
 *       instalment backwards, so future autopay amounts stay the same.</li>
 *   <li>Anything left is an overpayment held for the member.</li>
 * </ol>
 * An overpayment refund larger than the overpayment then held re-opens the latest-maturity
 * instalments, so the overpayment never goes negative.
 */
public final class Allocator {

    /** Portion seq used for money held as overpayment rather than applied to an instalment. */
    public static final int OVERPAYMENT_SEQ = 0;

    private static final Comparator<MoneyEvent> PROCESSING_ORDER =
            Comparator.comparing(MoneyEvent::effectiveDate).thenComparing(MoneyEvent::id);

    private Allocator() {
    }

    public static Allocation allocate(Schedule schedule, Collection<? extends MoneyEvent> events, RepaymentPolicy policy) {
        Map<UUID, Long> netByReceipt = netReceipts(events);
        List<MoneyEvent> ordered = events.stream()
                .filter(e -> e instanceof OverpaymentRefund
                        || (e instanceof Receipt r && netByReceipt.get(r.id()) > 0))
                .map(e -> (MoneyEvent) e)
                .sorted(PROCESSING_ORDER)
                .toList();

        Fold fold = new Fold(schedule);
        Map<UUID, List<Portion>> portions = new LinkedHashMap<>();
        for (MoneyEvent event : ordered) {
            List<Portion> applied = switch (event) {
                case Receipt r -> fold.receive(r.effectiveDate(), netByReceipt.get(r.id()), policy);
                case OverpaymentRefund f -> fold.refundOverpayment(f.amountCents());
                case Reversal ignored -> throw new IllegalStateException("reversals are folded into receipts");
            };
            portions.put(event.id(), applied);
        }
        return fold.result(portions);
    }

    private static Map<UUID, Long> netReceipts(Collection<? extends MoneyEvent> events) {
        Map<UUID, Long> net = new HashMap<>();
        for (MoneyEvent e : events) {
            if (e instanceof Receipt r && net.put(r.id(), r.amountCents()) != null) {
                throw new IllegalArgumentException("duplicate receipt " + r.id());
            }
        }
        for (MoneyEvent e : events) {
            if (e instanceof Reversal v) {
                Long current = net.get(v.receiptId());
                if (current == null) {
                    throw new IllegalArgumentException("reversal " + v.id() + " references unknown receipt " + v.receiptId());
                }
                long remaining = current - v.amountCents();
                if (remaining < 0) {
                    throw new IllegalArgumentException("reversals exceed receipt " + v.receiptId());
                }
                net.put(v.receiptId(), remaining);
            }
        }
        return net;
    }

    /** Mutable working state of one fold; never escapes {@link #allocate}. */
    private static final class Fold {
        private final List<Instalment> instalments;
        private final long[] paid;
        private final long[] paidInAdvance;
        private final LocalDate[] fullyPaidOn;
        private long overpayment;

        Fold(Schedule schedule) {
            this.instalments = schedule.instalments();
            this.paid = new long[instalments.size()];
            this.paidInAdvance = new long[instalments.size()];
            this.fullyPaidOn = new LocalDate[instalments.size()];
        }

        List<Portion> receive(LocalDate date, long amount, RepaymentPolicy policy) {
            List<Portion> out = new ArrayList<>();
            LocalDate horizon = date.plusDays(policy.dueWindowDays());
            long left = amount;
            for (int i = 0; i < instalments.size() && left > 0; i++) {
                if (instalments.get(i).dueDate().isAfter(horizon)) {
                    break;
                }
                left = pay(i, left, date, false, out);
            }
            if (policy.futureInstalmentRule() == FutureInstalmentRule.LAST_INSTALLMENT) {
                for (int i = instalments.size() - 1; i >= 0 && left > 0; i--) {
                    left = pay(i, left, date, true, out);
                }
            } else {
                for (int i = 0; i < instalments.size() && left > 0; i++) {
                    left = pay(i, left, date, true, out);
                }
            }
            if (left > 0) {
                overpayment += left;
                out.add(new Portion(OVERPAYMENT_SEQ, left));
            }
            return out;
        }

        List<Portion> refundOverpayment(long amount) {
            List<Portion> out = new ArrayList<>();
            long fromPool = Math.min(overpayment, amount);
            overpayment -= fromPool;
            if (fromPool > 0) {
                out.add(new Portion(OVERPAYMENT_SEQ, -fromPool));
            }
            long shortfall = amount - fromPool;
            for (int i = instalments.size() - 1; i >= 0 && shortfall > 0; i--) {
                long take = Math.min(paid[i], shortfall);
                if (take > 0) {
                    paid[i] -= take;
                    paidInAdvance[i] -= Math.min(paidInAdvance[i], take);
                    fullyPaidOn[i] = null;
                    shortfall -= take;
                    out.add(new Portion(i + 1, -take));
                }
            }
            if (shortfall > 0) {
                throw new IllegalStateException("refunds exceed all money received for this loan");
            }
            return out;
        }

        private long pay(int i, long available, LocalDate date, boolean inAdvance, List<Portion> out) {
            long take = Math.min(available, instalments.get(i).amountCents() - paid[i]);
            if (take <= 0) {
                return available;
            }
            paid[i] += take;
            if (inAdvance) {
                paidInAdvance[i] += take;
            }
            if (paid[i] == instalments.get(i).amountCents()) {
                fullyPaidOn[i] = date;
            }
            out.add(new Portion(i + 1, take));
            return available - take;
        }

        Allocation result(Map<UUID, List<Portion>> portions) {
            List<InstalmentAllocation> rows = new ArrayList<>(instalments.size());
            for (int i = 0; i < instalments.size(); i++) {
                Instalment inst = instalments.get(i);
                rows.add(new InstalmentAllocation(inst.seq(), inst.amountCents(), paid[i], paidInAdvance[i], fullyPaidOn[i]));
            }
            return new Allocation(rows, overpayment, portions);
        }
    }
}
