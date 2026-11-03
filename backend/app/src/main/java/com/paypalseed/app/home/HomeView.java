package com.paypalseed.app.home;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything the member Home page needs (ADR 0018). A section is {@code null} when its module failed or has nothing
 * for the member, and {@code sectionErrors} says which and why. Nothing here is a risk level, a score or a tier.
 *
 * <p>{@code payoutRecipientReady} is not a section: it is true when identity holds a payout recipient for the member and
 * false when it does not (a withdrawal or a loan payout would then be refused with {@code payout-recipient-missing}), so
 * the web can warn first (ADR 0027). It is {@code null}, and left out of the JSON, only when identity could not answer.
 */
public record HomeView(
        LocalDate generatedOn,
        Boolean payoutRecipientReady,
        ProfileSection profile,
        SavingsSection savings,
        LendingSection lending,
        UnderwritingSection underwriting,
        CreditRecordSection creditRecord,
        HardshipSection hardship,
        List<ActivityItem> recentActivity,
        List<SectionError> sectionErrors) {

    public HomeView {
        recentActivity = recentActivity == null ? null : List.copyOf(recentActivity);
        sectionErrors = List.copyOf(sectionErrors);
    }

    public enum Section {
        profile, savings, lending, underwriting, creditRecord, hardship, recentActivity
    }

    /** Stable codes: {@code no-data} (nothing yet) and {@code unavailable} (the module failed; try again). */
    public enum ErrorCode {
        @JsonProperty("no-data")
        NO_DATA,
        @JsonProperty("unavailable")
        UNAVAILABLE
    }

    public record SectionError(Section section, ErrorCode code) {
    }

    public record MoneyView(long cents, String currency) {
        static MoneyView usd(long cents) {
            return new MoneyView(cents, "USD");
        }
    }

    public record ProfileSection(String displayName, String language, String walletPreference) {
    }

    public record SavingsSection(int currentStreakMonths, MoneyView monthlySaving, MoneyView totalSaved,
                                 int missedDepositsInQuarter, boolean microloanEligible, List<GoalCard> goals) {
        public SavingsSection {
            goals = goals == null ? null : List.copyOf(goals);
        }
    }

    /**
     * {@code nextDepositDate} is the business date PayPal bills the monthly deposit next, as PayPal last reported it. It is
     * null when the goal is not saving by subscription right now (paused, waiting for approval, ended, Venmo), when PayPal
     * has not said, and when the date it said has already passed (the report is stale until the next one arrives).
     */
    public record GoalCard(UUID goalId, String name, String status, MoneyView target, MoneyView saved, MoneyView monthly,
                           LocalDate targetDate, LocalDate nextDepositDate) {
    }

    public record LendingSection(EligibilityCard eligibility, ActiveLoanCard activeLoan, OpenApplicationCard openApplication) {
    }

    public record EligibilityCard(boolean eligible, MoneyView maxLoan, MoneyView maxMonthlyInstalment, int minTermMonths,
                                  int maxTermMonths, List<String> reasons) {
        public EligibilityCard {
            reasons = List.copyOf(reasons);
        }
    }

    public record ActiveLoanCard(UUID loanId, String status, MoneyView outstanding, NextPayment nextPayment,
                                 AutopayCard autopay) {
    }

    public record NextPayment(int seq, LocalDate dueDate, MoneyView amount, MoneyView remaining) {
    }

    public record AutopayCard(String status, LocalDate nextBillingDate) {
    }

    /** {@code payoutIssue} is a PayPal reason name when the last payout did not complete; the web maps it to words. */
    public record OpenApplicationCard(UUID applicationId, String status, MoneyView requested, int termMonths,
                                      Instant coolingOffExpiresAt, String payoutIssue) {
    }

    public record UnderwritingSection(boolean incomeConsent, AssessmentStatus latestAssessment) {
    }

    public record AssessmentStatus(UUID assessmentId, String decision, boolean memberDisputed, Instant createdAt) {
    }

    public record CreditRecordSection(String level, LocalDate since, NextStep nextStep) {
    }

    /**
     * {@code onTimeSavingMonthsToGo} is the months of on-time saving the next level still needs, computed by the level
     * rules from the member's record (0 once the saving part is met, even if loans or the on-time share still hold the level
     * back). {@code requiredOnTimeSavingMonths} is what the next level asks in all, so the page can draw "4 of 6" without a
     * rule of its own; it is null only if the rules name no such level.
     */
    public record NextStep(String nextLevel, int onTimeSavingMonthsToGo, int loansRepaidOnPlanToGo,
                           Integer requiredOnTimeSavingMonths, String hint) {
    }

    /**
     * One thing that happened in the member's Seed account, in plain structured form. {@code kind} is DEPOSIT, WITHDRAWAL,
     * WITHDRAWAL_RETURNED, LOAN_DISBURSED, LOAN_PAYMENT, LOAN_PAYMENT_REVERSED, COVER_APPLIED or LOAN_VOIDED; the web maps
     * it to words. {@code goalId} is set for savings kinds, {@code loanId} for loan kinds. There is no hardship detail here.
     */
    public record ActivityItem(String kind, MoneyView amount, Instant occurredAt, UUID goalId, UUID loanId) {
    }

    /** {@code dueDate} and {@code amountAtStake} describe the payment the open case is about; null with no case. */
    public record HardshipSection(boolean offerWaiting, UUID caseId, UUID loanId, String status, LocalDate dueDate,
                                  MoneyView amountAtStake) {

        public HardshipSection(boolean offerWaiting, UUID caseId, UUID loanId, String status) {
            this(offerWaiting, caseId, loanId, status, null, null);
        }
    }
}
