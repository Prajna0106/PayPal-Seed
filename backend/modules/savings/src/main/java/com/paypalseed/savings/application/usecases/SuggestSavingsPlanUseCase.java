package com.paypalseed.savings.application.usecases;

import com.paypalseed.savings.api.SavingsCoachNarrator;
import com.paypalseed.savings.api.SavingsCoachNarrator.CoachNarrationInput;
import com.paypalseed.savings.api.SavingsCoachNarrator.CoachNarrationResult;
import com.paypalseed.savings.api.dto.CoachSuggestionRequest;
import com.paypalseed.savings.api.dto.CoachSuggestionResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic calculator + grounded narrator for Features 1 & 2:
 * <ul>
 *   <li>Feature 1 (Goal Savings): suggests a realistic monthly saving amount (`$5` to `$150`, rounded to nearest `$5`)
 *       from stated monthly income and expenses.</li>
 *   <li>Feature 2 (Savings-Linked Microloan): computes the Level 1 loan unlocked after 4 months of on-time saving
 *       (up to `$1,500`) and picks a comfortable repayment term (`3` to `12` months) so the monthly instalment stays
 *       within `min(2x monthly saving, 15% of monthly income)`.</li>
 * </ul>
 */
public class SuggestSavingsPlanUseCase {

    private static final long MIN_MONTHLY_SAVING_CENTS = 500L;       // $5.00
    private static final long MAX_MONTHLY_SAVING_CENTS = 15_000L;    // $150.00
    private static final long STEP_CENTS = 500L;                     // $5.00 rounding step
    private static final int STREAK_MONTHS_TO_UNLOCK = 4;
    private static final long LEVEL_1_MAX_LOAN_CENTS = 150_000L;     // $1,500.00
    private static final long MIN_LOAN_CENTS = 30_000L;              // $300.00
    private static final int MIN_TERM_MONTHS = 3;
    private static final int MAX_TERM_MONTHS = 12;

    private final SavingsCoachNarrator narrator;

    public SuggestSavingsPlanUseCase() {
        this(null);
    }

    public SuggestSavingsPlanUseCase(SavingsCoachNarrator narrator) {
        this.narrator = narrator;
    }

    public CoachSuggestionResponse execute(CoachSuggestionRequest request) {
        Objects.requireNonNull(request, "request is required");
        if (request.statedMonthlyIncome() == null || request.statedMonthlyIncome().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("statedMonthlyIncome must be positive");
        }

        long incomeCents = toCents(request.statedMonthlyIncome());
        long expensesCents = request.statedMonthlyExpenses() != null && request.statedMonthlyExpenses().compareTo(BigDecimal.ZERO) > 0
            ? toCents(request.statedMonthlyExpenses())
            : 0L;
        long disposableCents = Math.max(0L, incomeCents - expensesCents);

        // Suggest ~10% of disposable income (or 5% of stated income if expenses were not provided / equal income),
        // rounded to the nearest $5 and clamped to [$5, $150].
        long baseSourceCents = disposableCents > 0 ? (disposableCents * 10L) / 100L : (incomeCents * 5L) / 100L;
        long roundedSavingCents = Math.round((double) baseSourceCents / STEP_CENTS) * STEP_CENTS;
        long suggestedSavingCents = Math.max(MIN_MONTHLY_SAVING_CENTS, Math.min(MAX_MONTHLY_SAVING_CENTS, roundedSavingCents));

        long totalSavedAfterStreakCents = suggestedSavingCents * STREAK_MONTHS_TO_UNLOCK;

        long targetCents = request.targetAmount() != null && request.targetAmount().compareTo(BigDecimal.ZERO) > 0
            ? toCents(request.targetAmount())
            : LEVEL_1_MAX_LOAN_CENTS;

        // Feature 2: 2x monthly saving cap and 15% of monthly income cap
        long twoTimesSavingCents = suggestedSavingCents * 2L;
        long fifteenPctIncomeCents = (incomeCents * 15L) / 100L;
        long maxInstalmentCents = Math.max(MIN_MONTHLY_SAVING_CENTS, Math.min(twoTimesSavingCents, fifteenPctIncomeCents));

        // Max comfortable loan over 12 months within Level 1 ($1,500)
        long maxAffordableOverMaxTermCents = Math.min(LEVEL_1_MAX_LOAN_CENTS, maxInstalmentCents * MAX_TERM_MONTHS);
        long unlockedLoanCents = Math.max(MIN_LOAN_CENTS, Math.min(targetCents, maxAffordableOverMaxTermCents));
        // Round unlocked loan to nearest $50 (5,000 cents) if >= $300
        unlockedLoanCents = Math.max(MIN_LOAN_CENTS, (unlockedLoanCents / 5_000L) * 5_000L);

        int rawTermMonths = (int) Math.ceil((double) unlockedLoanCents / (double) maxInstalmentCents);
        int suggestedTermMonths = Math.max(MIN_TERM_MONTHS, Math.min(MAX_TERM_MONTHS, rawTermMonths));
        long suggestedInstalmentCents = (unlockedLoanCents + suggestedTermMonths - 1) / suggestedTermMonths;

        String goalName = (request.goalName() == null || request.goalName().isBlank())
            ? "Emergency & Business Goal"
            : request.goalName().strip();

        String templateEn = String.format(
            Locale.US,
            "Saving %s a month for %d months builds %s in your own reserve and unlocks a %s zero-interest loan for %s, repaid at %s a month over %d months.",
            dollars(suggestedSavingCents),
            STREAK_MONTHS_TO_UNLOCK,
            dollars(totalSavedAfterStreakCents),
            dollars(unlockedLoanCents),
            goalName,
            dollars(suggestedInstalmentCents),
            suggestedTermMonths
        );

        String templateEs = String.format(
            Locale.US,
            "Ahorrar %s al mes durante %d meses crea %s en tu propia reserva y desbloquea un préstamo sin intereses de %s para %s, con pagos de %s al mes durante %d meses.",
            dollars(suggestedSavingCents),
            STREAK_MONTHS_TO_UNLOCK,
            dollars(totalSavedAfterStreakCents),
            dollars(unlockedLoanCents),
            goalName,
            dollars(suggestedInstalmentCents),
            suggestedTermMonths
        );

        CoachNarrationInput narrationInput = new CoachNarrationInput(
            request.memberId() != null ? request.memberId() : "anonymous",
            goalName,
            targetCents,
            incomeCents,
            expensesCents,
            disposableCents,
            suggestedSavingCents,
            STREAK_MONTHS_TO_UNLOCK,
            totalSavedAfterStreakCents,
            "LEVEL_1",
            unlockedLoanCents,
            maxInstalmentCents,
            suggestedTermMonths,
            suggestedInstalmentCents,
            templateEn,
            templateEs
        );

        CoachNarrationResult narration = narrator != null
            ? narrator.narrate(narrationInput)
            : new CoachNarrationResult(templateEn, templateEs, false);

        return new CoachSuggestionResponse(
            request.memberId(),
            goalName,
            request.effectiveCurrency(),
            fromCents(targetCents),
            fromCents(incomeCents),
            fromCents(expensesCents),
            fromCents(disposableCents),
            fromCents(suggestedSavingCents),
            STREAK_MONTHS_TO_UNLOCK,
            fromCents(totalSavedAfterStreakCents),
            "LEVEL_1",
            fromCents(unlockedLoanCents),
            fromCents(maxInstalmentCents),
            suggestedTermMonths,
            fromCents(suggestedInstalmentCents),
            narration.messageEn(),
            narration.messageEs(),
            narration.modelGenerated()
        );
    }

    private static long toCents(BigDecimal dollars) {
        return dollars.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    private static String dollars(long cents) {
        if (cents % 100 == 0) {
            return String.format(Locale.US, "$%,d", cents / 100);
        }
        return String.format(Locale.US, "$%,.2f", cents / 100.0);
    }
}
