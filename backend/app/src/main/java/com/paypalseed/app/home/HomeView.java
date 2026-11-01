package com.paypalseed.app.home;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything the member Home page needs (ADR 0018). A section is {@code null} when its module failed or has nothing
 * for the member, and {@code sectionErrors} says which and why. Nothing here is a risk level, a score or a tier.
 */
public record HomeView(
        LocalDate generatedOn,
        ProfileSection profile,
        SavingsSection savings,
        LendingSection lending,
        UnderwritingSection underwriting,
        CreditRecordSection creditRecord,
        HardshipSection hardship,
        List<SectionError> sectionErrors) {

    public HomeView {
        sectionErrors = List.copyOf(sectionErrors);
    }

    public enum Section {
        profile, savings, lending, underwriting, creditRecord, hardship
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

    public record GoalCard(UUID goalId, String name, String status, MoneyView target, MoneyView saved, MoneyView monthly,
                           LocalDate targetDate) {
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

    public record NextStep(String nextLevel, int onTimeSavingMonthsToGo, int loansRepaidOnPlanToGo, String hint) {
    }

    /** {@code dueDate} and {@code amountAtStake} describe the payment the open case is about; null with no case. */
    public record HardshipSection(boolean offerWaiting, UUID caseId, UUID loanId, String status, LocalDate dueDate,
                                  MoneyView amountAtStake) {

        public HardshipSection(boolean offerWaiting, UUID caseId, UUID loanId, String status) {
            this(offerWaiting, caseId, loanId, status, null, null);
        }
    }
}
