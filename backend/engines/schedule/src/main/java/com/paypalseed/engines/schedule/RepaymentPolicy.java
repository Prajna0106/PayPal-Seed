package com.paypalseed.engines.schedule;

import java.util.Map;
import java.util.Objects;

/**
 * Versioned repayment policy (01-requirements §1). A loan keeps the policy version it was signed
 * under, so changing a value never rewrites the terms of an existing loan.
 *
 * @param graceDays                 an unpaid instalment is late only when days past due exceed this
 * @param minFirstDueLeadDays       first due date is at least this many days after disbursement
 * @param reminderLeadDays          days before a due date that the "your payment is in N days" reminder goes out
 * @param graceNudgeDpd             day of grace on which the catch-up nudge goes out
 * @param dueWindowDays             a payment settles instalments due up to this many days ahead
 * @param futureInstalmentRule      where money beyond what is due goes (Fineract's future-installment rule)
 * @param remainderPlacement        where the remainder dollars of an uneven split go
 * @param retryMinDpd               earliest day past due for the single automatic retry
 * @param retryMaxDpd               latest day past due for that retry (result known before grace ends)
 * @param retryMinGapDays           minimum days between a failed charge and its retry
 * @param maxMoveDays               hardship option A: largest date move
 * @param maxExtraMonths            hardship option C: most months that can be added
 * @param minInstalmentCents        hardship option C: smallest instalment allowed
 * @param mandateDateToleranceDays  PayPal billing date may differ from a due date by this much
 */
public record RepaymentPolicy(
        String version,
        int graceDays,
        int minFirstDueLeadDays,
        int reminderLeadDays,
        int graceNudgeDpd,
        int dueWindowDays,
        FutureInstalmentRule futureInstalmentRule,
        RemainderPlacement remainderPlacement,
        int retryMinDpd,
        int retryMaxDpd,
        int retryMinGapDays,
        int maxMoveDays,
        int maxExtraMonths,
        long minInstalmentCents,
        int mandateDateToleranceDays,
        int maxInstalments) {

    /** Where prepayments go once everything due has been paid. */
    public enum FutureInstalmentRule {
        /** Shorten the loan from the end (inverse order of maturity). Default; ADR 0006. */
        LAST_INSTALLMENT,
        /** Pay ahead on the next instalment. */
        NEXT_INSTALLMENT
    }

    /** Where the remainder dollars of an uneven split go (03-algorithms §2). */
    public enum RemainderPlacement {
        FRONT,
        BACK
    }

    public static final RepaymentPolicy V1 = new RepaymentPolicy(
            "repayment-v1", 5, 14, 3, 3, 7,
            FutureInstalmentRule.LAST_INSTALLMENT, RemainderPlacement.BACK,
            2, 4, 2, 14, 6, 2_500, 3, 36);

    private static final Map<String, RepaymentPolicy> BY_VERSION = Map.of(V1.version(), V1);

    public RepaymentPolicy {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(futureInstalmentRule, "futureInstalmentRule");
        Objects.requireNonNull(remainderPlacement, "remainderPlacement");
        if (graceNudgeDpd < 1 || graceNudgeDpd > graceDays) {
            throw new IllegalArgumentException("graceNudgeDpd must fall inside the grace period");
        }
        if (retryMinDpd < 1 || retryMaxDpd > graceDays || retryMinDpd > retryMaxDpd) {
            throw new IllegalArgumentException("retry window must fall inside the grace period");
        }
        if (minInstalmentCents <= 0 || minInstalmentCents % 100 != 0) {
            throw new IllegalArgumentException("minInstalmentCents must be whole dollars");
        }
    }

    public static RepaymentPolicy forVersion(String version) {
        RepaymentPolicy policy = BY_VERSION.get(version);
        if (policy == null) {
            throw new IllegalArgumentException("Unknown repayment policy version: " + version);
        }
        return policy;
    }
}
