package com.paypalseed.engines.rules;

import com.paypalseed.engines.rules.record.RecordLevel;
import com.paypalseed.engines.rules.record.RecordLimits;
import com.paypalseed.platform.kernel.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Pure deterministic engine evaluating member microloan ladder eligibility and sizing rules (Blueprint §5).
 */
public final class EligibilityEngine {

    public static final int MIN_SAVING_STREAK_MONTHS = 3;
    public static final int MAX_MISSED_DEPOSITS_PER_QUARTER = 1;
    public static final long MIN_LOAN_CENTS = 5_000L; // $50.00 minimum microloan

    private EligibilityEngine() {
    }

    public record EligibilityInput(
        int currentStreakMonths,
        int totalOnTimeDeposits,
        int missedDepositsInQuarter,
        long monthlySavingCents,
        OptionalLong verifiedMonthlyIncomeCents,
        int completedLoansOnTime,
        boolean hasActiveLoan,
        RecordLevel recordLevel
    ) {
        public EligibilityInput {
            if (verifiedMonthlyIncomeCents == null) {
                verifiedMonthlyIncomeCents = OptionalLong.empty();
            }
            if (recordLevel == null) {
                recordLevel = RecordLevel.NEW;
            }
        }

        /** An input with no credit record: the ladder alone decides, as before the record existed. */
        public EligibilityInput(
            int currentStreakMonths,
            int totalOnTimeDeposits,
            int missedDepositsInQuarter,
            long monthlySavingCents,
            OptionalLong verifiedMonthlyIncomeCents,
            int completedLoansOnTime,
            boolean hasActiveLoan
        ) {
            this(currentStreakMonths, totalOnTimeDeposits, missedDepositsInQuarter, monthlySavingCents,
                verifiedMonthlyIncomeCents, completedLoansOnTime, hasActiveLoan, RecordLevel.NEW);
        }
    }

    /**
     * @param tier           the rung of the ladder that applies: the higher of what repaid loans earned and what the
     *                       credit record unlocks
     * @param tierLimitCents the loan ceiling that applies: the larger of the rung's and the record level's
     * @param recordLevel    the level of the member's credit record that was taken into account
     * @param limitReason    which limit applied and why, in plain words (no scores); empty when not eligible
     */
    public record EligibilityEvaluation(
        boolean eligible,
        LoanTier tier,
        long tierLimitCents,
        long maxLoanCents,
        long maxMonthlyInstalmentCents,
        int minTermMonths,
        int maxTermMonths,
        List<String> reasons,
        RecordLevel recordLevel,
        String limitReason
    ) {
        public EligibilityEvaluation {
            Objects.requireNonNull(tier, "tier is required");
            reasons = List.copyOf(reasons);
            Objects.requireNonNull(recordLevel, "recordLevel is required");
            Objects.requireNonNull(limitReason, "limitReason is required");
        }
    }

    public record SizingDecision(
        boolean allowed,
        long requestedPrincipalCents,
        int requestedTermMonths,
        long requiredMonthlyInstalmentCents,
        long maxMonthlyInstalmentCents,
        long suggestedPrincipalCents,
        int suggestedTermMonths,
        List<String> reasons
    ) {
        public SizingDecision {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * Evaluates which tier of the loan ladder a member unlocks and their maximum borrowing power.
     */
    public static EligibilityEvaluation evaluate(EligibilityInput input) {
        Objects.requireNonNull(input, "input is required");
        List<String> reasons = new ArrayList<>();

        boolean streakMet = input.currentStreakMonths() >= MIN_SAVING_STREAK_MONTHS
            && input.totalOnTimeDeposits() >= MIN_SAVING_STREAK_MONTHS;
        boolean graceMet = input.missedDepositsInQuarter() <= MAX_MISSED_DEPOSITS_PER_QUARTER;
        boolean savingAmountValid = input.monthlySavingCents() >= 500L; // at least $5/month

        if (!streakMet) {
            int remaining = Math.max(1, MIN_SAVING_STREAK_MONTHS - input.currentStreakMonths());
            reasons.add("Needs " + remaining + " more month(s) of steady saving to reach the 3-month unlock threshold.");
        }
        if (!graceMet) {
            reasons.add("More than " + MAX_MISSED_DEPOSITS_PER_QUARTER + " missed deposit in the current quarter ("
                + input.missedDepositsInQuarter() + " missed).");
        }
        if (!savingAmountValid) {
            reasons.add("Requires an active monthly savings habit of at least $5.00.");
        }

        if (!streakMet || !graceMet || !savingAmountValid) {
            return new EligibilityEvaluation(
                false,
                LoanTier.UNQUALIFIED,
                0L,
                0L,
                0L,
                0,
                0,
                reasons,
                input.recordLevel(),
                ""
            );
        }

        // The record only ever raises what the ladder allows: the higher rung, the larger ceiling.
        LoanTier ladderTier = resolveTier(input.completedLoansOnTime());
        RecordLevel level = input.recordLevel();
        LoanTier tier = level.ladderTier().level() > ladderTier.level() ? level.ladderTier() : ladderTier;
        long limit = RecordLimits.limitCents(level, ladderTier.maxLoanCents());
        String limitReason = limitReason(level, ladderTier.maxLoanCents(), limit);
        long maxMonthlyInstalment = SizingRule.maxMonthlyInstalmentCents(
            input.monthlySavingCents(),
            input.verifiedMonthlyIncomeCents()
        );

        if (input.hasActiveLoan() && !tier.allowsConcurrentLoans()) {
            reasons.add("Only one active microloan is permitted at a time at " + tier.name() + ".");
            return new EligibilityEvaluation(
                false,
                tier,
                limit,
                0L,
                maxMonthlyInstalment,
                tier.minTermMonths(),
                tier.maxTermMonths(),
                reasons,
                level,
                limitReason
            );
        }

        if (maxMonthlyInstalment <= 0) {
            reasons.add("Calculated monthly instalment capacity is $0.00.");
            return new EligibilityEvaluation(
                false,
                tier,
                limit,
                0L,
                0L,
                tier.minTermMonths(),
                tier.maxTermMonths(),
                reasons,
                level,
                limitReason
            );
        }

        long maxSafePrincipalAtMaxTerm = SizingRule.maxSafePrincipalCents(
            maxMonthlyInstalment,
            tier.maxTermMonths(),
            limit
        );

        reasons.add(String.format(
            "Unlocked %s (%s limit) with %d months of consistent saving (%s/month).",
            tier.name(),
            Money.ofCents(limit),
            input.currentStreakMonths(),
            Money.ofCents(input.monthlySavingCents())
        ));
        reasons.add(String.format(
            "Maximum monthly instalment capped at %s (2x monthly savings%s).",
            Money.ofCents(maxMonthlyInstalment),
            input.verifiedMonthlyIncomeCents().isPresent()
                ? " and 15% of " + Money.ofCents(input.verifiedMonthlyIncomeCents().getAsLong()) + " verified monthly income"
                : ""
        ));

        reasons.add(limitReason);

        return new EligibilityEvaluation(
            true,
            tier,
            limit,
            maxSafePrincipalAtMaxTerm,
            maxMonthlyInstalment,
            tier.minTermMonths(),
            tier.maxTermMonths(),
            reasons,
            level,
            limitReason
        );
    }

    /**
     * Which ceiling applies and why, for the member to read: the level of the record by name, never a score, and the
     * amounts as the member would say them.
     */
    static String limitReason(RecordLevel level, long ladderLimitCents, long limitCents) {
        String name = displayName(level);
        if (limitCents > ladderLimitCents) {
            return String.format("Your record is at %s, which lifts your limit from %s to %s.", name,
                Money.ofCents(ladderLimitCents), Money.ofCents(limitCents));
        }
        if (level == RecordLevel.NEW) {
            return String.format("Your limit is %s. It grows as you save and repay on time.", Money.ofCents(limitCents));
        }
        return String.format("Your record is at %s and your limit is %s. It grows as you keep saving and repaying on time.", name,
            Money.ofCents(limitCents));
    }

    private static String displayName(RecordLevel level) {
        String lower = level.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Checks a specific loan request against the member's eligibility and sizing rules.
     * When a request exceeds limits, blocks it and suggests the largest safe amount or a longer term.
     */
    public static SizingDecision checkApplication(
        EligibilityInput input,
        long requestedPrincipalCents,
        int requestedTermMonths
    ) {
        EligibilityEvaluation eval = evaluate(input);
        List<String> reasons = new ArrayList<>();

        if (!eval.eligible()) {
            reasons.addAll(eval.reasons());
            return new SizingDecision(
                false,
                requestedPrincipalCents,
                requestedTermMonths,
                0L,
                eval.maxMonthlyInstalmentCents(),
                0L,
                0,
                reasons
            );
        }

        LoanTier tier = eval.tier();
        long limit = eval.tierLimitCents(); // the rung's ceiling, or the record level's when that is higher
        long maxMonthly = eval.maxMonthlyInstalmentCents();

        // Validate positive & whole dollars
        if (requestedPrincipalCents < MIN_LOAN_CENTS || requestedPrincipalCents % 100L != 0) {
            reasons.add("Loan amount must be in whole dollars and at least " + Money.ofCents(MIN_LOAN_CENTS) + ".");
            int fallbackTerm = clampTerm(requestedTermMonths, tier);
            long suggestedPrincipal = SizingRule.maxSafePrincipalCents(maxMonthly, fallbackTerm, limit);
            return new SizingDecision(
                false,
                requestedPrincipalCents,
                requestedTermMonths,
                0L,
                maxMonthly,
                suggestedPrincipal,
                fallbackTerm,
                reasons
            );
        }

        // Validate term range
        if (!tier.isValidTerm(requestedTermMonths)) {
            int clampedTerm = clampTerm(requestedTermMonths, tier);
            long suggestedPrincipal = Math.min(
                requestedPrincipalCents,
                SizingRule.maxSafePrincipalCents(maxMonthly, clampedTerm, limit)
            );
            reasons.add(String.format(
                "Requested term of %d months is outside %s allowed range (%d to %d months). Suggested term: %d months.",
                requestedTermMonths, tier.name(), tier.minTermMonths(), tier.maxTermMonths(), clampedTerm
            ));
            return new SizingDecision(
                false,
                requestedPrincipalCents,
                requestedTermMonths,
                SizingRule.requiredMonthlyInstalmentCents(requestedPrincipalCents, Math.max(1, requestedTermMonths)),
                maxMonthly,
                suggestedPrincipal,
                clampedTerm,
                reasons
            );
        }

        // Validate tier cap
        if (requestedPrincipalCents > limit) {
            long suggestedPrincipal = SizingRule.maxSafePrincipalCents(maxMonthly, requestedTermMonths, limit);
            int suggestedTerm = requestedTermMonths;
            if (suggestedPrincipal < limit) {
                int minTermForTierMax = SizingRule.minimumSafeTermMonths(limit, maxMonthly);
                if (minTermForTierMax <= tier.maxTermMonths()) {
                    suggestedPrincipal = limit;
                    suggestedTerm = Math.max(tier.minTermMonths(), minTermForTierMax);
                }
            }
            reasons.add(String.format(
                "Requested amount %s exceeds %s limit of %s. Largest safe amount is %s over %d months.",
                Money.ofCents(requestedPrincipalCents),
                tier.name(),
                Money.ofCents(limit),
                Money.ofCents(suggestedPrincipal),
                suggestedTerm
            ));
            return new SizingDecision(
                false,
                requestedPrincipalCents,
                requestedTermMonths,
                SizingRule.requiredMonthlyInstalmentCents(requestedPrincipalCents, requestedTermMonths),
                maxMonthly,
                suggestedPrincipal,
                suggestedTerm,
                reasons
            );
        }

        // Validate monthly instalment sizing cap
        long requiredInstalment = SizingRule.requiredMonthlyInstalmentCents(requestedPrincipalCents, requestedTermMonths);
        if (requiredInstalment > maxMonthly) {
            int minTermNeeded = SizingRule.minimumSafeTermMonths(requestedPrincipalCents, maxMonthly);
            long maxAmountAtRequestedTerm = SizingRule.maxSafePrincipalCents(maxMonthly, requestedTermMonths, limit);

            if (minTermNeeded <= tier.maxTermMonths()) {
                int suggestedTerm = Math.max(tier.minTermMonths(), minTermNeeded);
                reasons.add(String.format(
                    "Monthly instalment of %s exceeds your safe monthly cap of %s. Consider extending term to %d months (%s/month) or borrowing up to %s over %d months.",
                    Money.ofCents(requiredInstalment),
                    Money.ofCents(maxMonthly),
                    suggestedTerm,
                    Money.ofCents(SizingRule.requiredMonthlyInstalmentCents(requestedPrincipalCents, suggestedTerm)),
                    Money.ofCents(maxAmountAtRequestedTerm),
                    requestedTermMonths
                ));
                return new SizingDecision(
                    false,
                    requestedPrincipalCents,
                    requestedTermMonths,
                    requiredInstalment,
                    maxMonthly,
                    requestedPrincipalCents,
                    suggestedTerm,
                    reasons
                );
            } else {
                long maxAtLongestTerm = SizingRule.maxSafePrincipalCents(maxMonthly, tier.maxTermMonths(), limit);
                reasons.add(String.format(
                    "Monthly instalment of %s exceeds your safe monthly cap of %s. Largest safe amount for %d months is %s (or %s over %d months).",
                    Money.ofCents(requiredInstalment),
                    Money.ofCents(maxMonthly),
                    requestedTermMonths,
                    Money.ofCents(maxAmountAtRequestedTerm),
                    Money.ofCents(maxAtLongestTerm),
                    tier.maxTermMonths()
                ));
                return new SizingDecision(
                    false,
                    requestedPrincipalCents,
                    requestedTermMonths,
                    requiredInstalment,
                    maxMonthly,
                    maxAmountAtRequestedTerm,
                    requestedTermMonths,
                    reasons
                );
            }
        }

        // Passed all rules!
        reasons.add(String.format(
            "Approved by sizing rules: %s over %d months (%s/month is within your %s monthly cap at 0%% interest).",
            Money.ofCents(requestedPrincipalCents),
            requestedTermMonths,
            Money.ofCents(requiredInstalment),
            Money.ofCents(maxMonthly)
        ));

        return new SizingDecision(
            true,
            requestedPrincipalCents,
            requestedTermMonths,
            requiredInstalment,
            maxMonthly,
            requestedPrincipalCents,
            requestedTermMonths,
            reasons
        );
    }

    private static LoanTier resolveTier(int completedLoansOnTime) {
        if (completedLoansOnTime >= 2) {
            return LoanTier.LEVEL_3;
        }
        if (completedLoansOnTime == 1) {
            return LoanTier.LEVEL_2;
        }
        return LoanTier.LEVEL_1;
    }

    private static int clampTerm(int requested, LoanTier tier) {
        if (!tier.isQualified()) {
            return 0;
        }
        return Math.max(tier.minTermMonths(), Math.min(tier.maxTermMonths(), requested));
    }
}
