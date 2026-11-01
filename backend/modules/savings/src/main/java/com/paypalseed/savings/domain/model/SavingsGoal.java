package com.paypalseed.savings.domain.model;

import com.paypalseed.platform.kernel.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing a member's savings goal.
 */
public class SavingsGoal {

    public static final Money MIN_MONTHLY_DEPOSIT = Money.usd(5.00);

    private final UUID id;
    private final String memberId;
    private String name;
    private Money targetAmount;
    private Money monthlyAmount;
    private LocalDate targetDate;
    private String payPalSubscriptionId;
    private GoalStatus status;
    private Money currentBalance;
    private final Instant createdAt;
    private Instant updatedAt;

    public SavingsGoal(
        UUID id,
        String memberId,
        String name,
        Money targetAmount,
        Money monthlyAmount,
        LocalDate targetDate,
        String payPalSubscriptionId,
        GoalStatus status,
        Money currentBalance,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "Goal ID is required");
        this.memberId = Objects.requireNonNull(memberId, "Member ID is required");
        this.name = validateName(name);
        this.targetAmount = validateTargetAmount(targetAmount);
        this.monthlyAmount = validateMonthlyAmount(monthlyAmount);
        this.targetDate = targetDate;
        this.payPalSubscriptionId = payPalSubscriptionId;
        this.status = Objects.requireNonNull(status, "Status is required");
        this.currentBalance = currentBalance != null ? currentBalance : Money.zero(monthlyAmount.currency());
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    public static SavingsGoal create(
        String memberId,
        String name,
        Money targetAmount,
        Money monthlyAmount,
        LocalDate targetDate,
        String payPalSubscriptionId
    ) {
        Instant now = Instant.now();
        return new SavingsGoal(
            UUID.randomUUID(),
            memberId,
            name,
            targetAmount,
            monthlyAmount,
            targetDate,
            payPalSubscriptionId,
            GoalStatus.ACTIVE,
            Money.zero(monthlyAmount.currency()),
            now,
            now
        );
    }

    public void recordDeposit(Money depositAmount, String payPalCaptureId) {
        Objects.requireNonNull(depositAmount, "Deposit amount is required");
        if (!depositAmount.isPositive()) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        if (status == GoalStatus.CANCELLED) {
            throw new IllegalStateException("Cannot deposit into a cancelled savings goal");
        }

        this.currentBalance = this.currentBalance.add(depositAmount);
        this.updatedAt = Instant.now();

        if (this.currentBalance.isGreaterThanOrEqual(this.targetAmount) && this.status == GoalStatus.ACTIVE) {
            this.status = GoalStatus.COMPLETED;
        }
    }

    public void recordWithdrawal(Money withdrawalAmount) {
        Objects.requireNonNull(withdrawalAmount, "Withdrawal amount is required");
        if (!withdrawalAmount.isPositive()) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }
        if (withdrawalAmount.compareTo(this.currentBalance) > 0) {
            throw new IllegalArgumentException(
                "Insufficient savings balance: requested " + withdrawalAmount + " but balance is " + this.currentBalance
            );
        }

        this.currentBalance = this.currentBalance.subtract(withdrawalAmount);
        this.updatedAt = Instant.now();

        // If goal was completed but balance dipped below target, reopen as active
        if (this.status == GoalStatus.COMPLETED && this.currentBalance.isLessThan(this.targetAmount)) {
            this.status = GoalStatus.ACTIVE;
        }
    }

    public void pause() {
        if (this.status == GoalStatus.CANCELLED) {
            throw new IllegalStateException("Cannot pause a cancelled goal");
        }
        this.status = GoalStatus.PAUSED;
        this.updatedAt = Instant.now();
    }

    public void resume() {
        if (this.status == GoalStatus.CANCELLED) {
            throw new IllegalStateException("Cannot resume a cancelled goal");
        }
        this.status = GoalStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = GoalStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    private static String validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Goal name cannot be empty");
        }
        return name.trim();
    }

    private static Money validateTargetAmount(Money target) {
        Objects.requireNonNull(target, "Target amount is required");
        if (!target.isPositive()) {
            throw new IllegalArgumentException("Target amount must be strictly greater than zero");
        }
        return target;
    }

    private static Money validateMonthlyAmount(Money monthly) {
        Objects.requireNonNull(monthly, "Monthly amount is required");
        if (monthly.compareTo(MIN_MONTHLY_DEPOSIT) < 0) {
            throw new IllegalArgumentException("Minimum monthly savings amount is $5.00");
        }
        return monthly;
    }

    // Getters
    public UUID getId() { return id; }
    public String getMemberId() { return memberId; }
    public String getName() { return name; }
    public Money getTargetAmount() { return targetAmount; }
    public Money getMonthlyAmount() { return monthlyAmount; }
    public LocalDate getTargetDate() { return targetDate; }
    public String getPayPalSubscriptionId() { return payPalSubscriptionId; }
    public GoalStatus getStatus() { return status; }
    public Money getCurrentBalance() { return currentBalance; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setPayPalSubscriptionId(String payPalSubscriptionId) {
        this.payPalSubscriptionId = payPalSubscriptionId;
        this.updatedAt = Instant.now();
    }
}
