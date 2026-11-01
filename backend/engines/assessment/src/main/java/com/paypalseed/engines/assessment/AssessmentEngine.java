package com.paypalseed.engines.assessment;

import com.paypalseed.engines.rules.EligibilityEngine;
import com.paypalseed.engines.rules.EligibilityEngine.EligibilityEvaluation;
import com.paypalseed.engines.rules.EligibilityEngine.EligibilityInput;
import com.paypalseed.engines.rules.LoanTier;
import com.paypalseed.engines.rules.SizingRule;
import com.paypalseed.platform.kernel.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Pure deterministic engine for Feature 3: The Fair Loan Check (Blueprint §6).
 *
 * <p>Calculates 6-month average income, lowest month, income volatility (coefficient of variation),
 * saving consistency, volatility-adjusted safe loan sizing, reproducible inputs hash, and
 * transparent plain-language explanation cards with zero protected-class or proxy variables.
 */
public final class AssessmentEngine {

    public static final long FALLBACK_FIRST_LOAN_CAP_CENTS = 50_000L; // $500.00 when no PayPal income history exists
    public static final double HIGH_VOLATILITY_CV_THRESHOLD = 0.25;

    public static final List<String> USED_DATA_DISCLOSURE = List.of(
        "Savings record in Seed: months saved, misses, and current balance",
        "PayPal or Venmo income received in the last 6 months",
        "Member's stated monthly expenses",
        "Past Seed loans and repayments",
        "Stated purpose of the loan"
    );

    public static final List<String> NEVER_USED_DATA_DISCLOSURE = List.of(
        "Race, ethnicity, religion, sex, age, national origin, or disability",
        "Zip code, neighbourhood, or any geographic proxy",
        "Contacts or social graph",
        "Social media content",
        "Anything the member did not explicitly consent to"
    );

    private AssessmentEngine() {
    }

    public record MonthlyIncomePoint(String yearMonth, long amountCents) {
        public MonthlyIncomePoint {
            Objects.requireNonNull(yearMonth, "yearMonth is required");
            if (amountCents < 0) {
                throw new IllegalArgumentException("amountCents cannot be negative");
            }
        }
    }

    public record AssessmentInput(
        List<MonthlyIncomePoint> monthlyIncomes,
        long statedMonthlyIncomeCents,
        long statedMonthlyExpensesCents,
        long provenMonthlySavingCents,
        int monthsSaved,
        int targetMonthsWindow,
        int missedDepositsInQuarter,
        int completedLoansOnTime,
        boolean hasActiveLoan,
        long requestedAmountCents,
        int requestedTermMonths,
        String purpose
    ) {
        public AssessmentInput {
            monthlyIncomes = monthlyIncomes != null ? List.copyOf(monthlyIncomes) : List.of();
            purpose = purpose != null ? purpose.trim() : "";
        }
    }

    public record FairCheckResult(
        boolean recommended,
        LoanTier unlockedTier,
        boolean usedFallbackStatedIncome,
        long averageMonthlyIncomeCents,
        long lowestMonthlyIncomeCents,
        String lowestIncomeMonth,
        double coefficientOfVariation,
        boolean highIncomeVolatility,
        long provenMonthlySavingCents,
        int monthsSaved,
        int targetMonthsWindow,
        long instalmentCapCents,
        long requestedAmountCents,
        int requestedTermMonths,
        long suggestedLoanCents,
        int suggestedTermMonths,
        long suggestedMonthlyInstalmentCents,
        String inputsHash,
        List<String> plainLanguageReasons,
        List<String> officerQuestions,
        List<String> usedData,
        List<String> neverUsedData
    ) {
        public FairCheckResult {
            plainLanguageReasons = List.copyOf(plainLanguageReasons);
            officerQuestions = List.copyOf(officerQuestions);
            usedData = List.copyOf(usedData);
            neverUsedData = List.copyOf(neverUsedData);
        }
    }

    /**
     * Runs the deterministic Fair Loan Check calculation and generates the explanation card.
     */
    public static FairCheckResult assess(AssessmentInput input) {
        Objects.requireNonNull(input, "input is required");

        boolean fallback = input.monthlyIncomes().isEmpty();
        long avgIncomeCents;
        long lowestIncomeCents;
        String lowestMonth = "";
        double cv;

        if (fallback) {
            avgIncomeCents = Math.max(0L, input.statedMonthlyIncomeCents());
            lowestIncomeCents = avgIncomeCents;
            cv = 0.0;
        } else {
            long sum = 0L;
            long min = Long.MAX_VALUE;
            String minMonth = input.monthlyIncomes().get(0).yearMonth();
            for (MonthlyIncomePoint pt : input.monthlyIncomes()) {
                sum = Math.addExact(sum, pt.amountCents());
                if (pt.amountCents() < min) {
                    min = pt.amountCents();
                    minMonth = pt.yearMonth();
                }
            }
            int n = input.monthlyIncomes().size();
            avgIncomeCents = sum / n;
            lowestIncomeCents = min;
            lowestMonth = minMonth;
            cv = computeCoefficientOfVariation(input.monthlyIncomes(), avgIncomeCents);
        }

        boolean highVolatility = !fallback && cv > HIGH_VOLATILITY_CV_THRESHOLD;

        EligibilityInput eligInput = new EligibilityInput(
            input.monthsSaved(),
            input.monthsSaved(),
            input.missedDepositsInQuarter(),
            input.provenMonthlySavingCents(),
            avgIncomeCents > 0 ? OptionalLong.of(avgIncomeCents) : OptionalLong.empty(),
            input.completedLoansOnTime(),
            input.hasActiveLoan()
        );

        EligibilityEvaluation eval = EligibilityEngine.evaluate(eligInput);
        LoanTier tier = eval.tier();

        long effectiveTierCapCents = tier.maxLoanCents();
        if (fallback && tier == LoanTier.LEVEL_1) {
            effectiveTierCapCents = Math.min(effectiveTierCapCents, FALLBACK_FIRST_LOAN_CAP_CENTS);
        }

        long instalmentCapCents = eval.maxMonthlyInstalmentCents();

        List<String> reasons = new ArrayList<>();
        List<String> officerQuestions = new ArrayList<>();

        String inputsHash = computeInputsHash(input);

        if (!eval.eligible()) {
            reasons.addAll(eval.reasons());
            return new FairCheckResult(
                false,
                tier,
                fallback,
                avgIncomeCents,
                lowestIncomeCents,
                lowestMonth,
                cv,
                highVolatility,
                input.provenMonthlySavingCents(),
                input.monthsSaved(),
                Math.max(input.monthsSaved(), input.targetMonthsWindow()),
                instalmentCapCents,
                input.requestedAmountCents(),
                input.requestedTermMonths(),
                0L,
                0,
                0L,
                inputsHash,
                reasons,
                officerQuestions,
                USED_DATA_DISCLOSURE,
                NEVER_USED_DATA_DISCLOSURE
            );
        }

        int effectiveTerm = Math.max(tier.minTermMonths(), Math.min(tier.maxTermMonths(), input.requestedTermMonths()));
        if (highVolatility && effectiveTerm < tier.maxTermMonths()) {
            // Volatility guard: suggest a longer term with a smaller monthly instalment
            effectiveTerm = Math.min(tier.maxTermMonths(), effectiveTerm + 2);
        }

        long maxSafeForTerm = SizingRule.maxSafePrincipalCents(instalmentCapCents, effectiveTerm, effectiveTierCapCents);
        long suggestedLoanCents = Math.min(input.requestedAmountCents(), maxSafeForTerm);

        // If requested amount fits in a longer term within tier limits, prefer extending term so member gets requested amount safely
        if (suggestedLoanCents < input.requestedAmountCents() && input.requestedAmountCents() <= effectiveTierCapCents) {
            int minTermNeeded = SizingRule.minimumSafeTermMonths(input.requestedAmountCents(), instalmentCapCents);
            if (minTermNeeded <= tier.maxTermMonths()) {
                effectiveTerm = Math.max(tier.minTermMonths(), minTermNeeded);
                suggestedLoanCents = input.requestedAmountCents();
            }
        }

        long suggestedMonthlyInstalmentCents = suggestedLoanCents > 0
            ? SizingRule.requiredMonthlyInstalmentCents(suggestedLoanCents, effectiveTerm)
            : 0L;

        boolean exactRequestApproved = suggestedLoanCents == input.requestedAmountCents()
            && effectiveTerm == input.requestedTermMonths()
            && suggestedMonthlyInstalmentCents <= instalmentCapCents;

        // Build plain-language explanation sentences citing every number used (Blueprint §6)
        int window = Math.max(input.monthsSaved(), input.targetMonthsWindow());
        reasons.add(String.format(
            Locale.US,
            "You saved %s every month for %d of %d months.",
            Money.ofCents(input.provenMonthlySavingCents()),
            input.monthsSaved(),
            window
        ));

        if (fallback) {
            reasons.add(String.format(
                Locale.US,
                "No 6-month PayPal/Venmo income history was found; used stated monthly income of %s with a $500.00 first-loan cap.",
                Money.ofCents(avgIncomeCents)
            ));
            officerQuestions.add("No PayPal/Venmo transaction history available; verify stated income of "
                + Money.ofCents(avgIncomeCents) + "/month.");
        } else {
            reasons.add(String.format(
                Locale.US,
                "Your average monthly income is %s (lowest month: %s)%s.",
                Money.ofCents(avgIncomeCents),
                Money.ofCents(lowestIncomeCents),
                highVolatility ? ", with noticeable month-to-month variation" : " and your income has been steady"
            ));
        }

        if (lowestIncomeCents > 0 && suggestedMonthlyInstalmentCents > 0) {
            double pctOfLowest = (suggestedMonthlyInstalmentCents * 100.0) / lowestIncomeCents;
            reasons.add(String.format(
                Locale.US,
                "%s a month over %d months is within your %s instalment cap (%.1f%% of your lowest month).",
                Money.ofCents(suggestedMonthlyInstalmentCents),
                effectiveTerm,
                Money.ofCents(instalmentCapCents),
                pctOfLowest
            ));
        }

        if (highVolatility) {
            officerQuestions.add(String.format(
                Locale.US,
                "Income dipped to %s in %s (CV = %.2f); ask if that dip was seasonal.",
                Money.ofCents(lowestIncomeCents),
                lowestMonth,
                cv
            ));
        }

        return new FairCheckResult(
            exactRequestApproved || suggestedLoanCents > 0,
            tier,
            fallback,
            avgIncomeCents,
            lowestIncomeCents,
            lowestMonth,
            cv,
            highVolatility,
            input.provenMonthlySavingCents(),
            input.monthsSaved(),
            window,
            instalmentCapCents,
            input.requestedAmountCents(),
            input.requestedTermMonths(),
            suggestedLoanCents,
            effectiveTerm,
            suggestedMonthlyInstalmentCents,
            inputsHash,
            reasons,
            officerQuestions,
            USED_DATA_DISCLOSURE,
            NEVER_USED_DATA_DISCLOSURE
        );
    }

    public static double computeCoefficientOfVariation(List<MonthlyIncomePoint> incomes, long meanCents) {
        if (incomes == null || incomes.size() <= 1 || meanCents <= 0) {
            return 0.0;
        }
        double sumSquaredDiff = 0.0;
        for (MonthlyIncomePoint pt : incomes) {
            double diff = pt.amountCents() - (double) meanCents;
            sumSquaredDiff += diff * diff;
        }
        double stdDev = Math.sqrt(sumSquaredDiff / incomes.size());
        double rawCv = stdDev / meanCents;
        return Math.round(rawCv * 10_000.0) / 10_000.0;
    }

    public static String computeInputsHash(AssessmentInput input) {
        StringBuilder sb = new StringBuilder();
        for (MonthlyIncomePoint pt : input.monthlyIncomes()) {
            sb.append(pt.yearMonth()).append(':').append(pt.amountCents()).append(';');
        }
        sb.append("|statedInc=").append(input.statedMonthlyIncomeCents())
            .append("|statedExp=").append(input.statedMonthlyExpensesCents())
            .append("|saving=").append(input.provenMonthlySavingCents())
            .append("|months=").append(input.monthsSaved())
            .append("|misses=").append(input.missedDepositsInQuarter())
            .append("|completed=").append(input.completedLoansOnTime())
            .append("|active=").append(input.hasActiveLoan())
            .append("|reqAmt=").append(input.requestedAmountCents())
            .append("|reqTerm=").append(input.requestedTermMonths());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
