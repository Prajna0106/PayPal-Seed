package com.paypalseed.savings.domain.model;

import com.paypalseed.platform.kernel.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    private int depositCount;
    private SavingsWallet wallet = SavingsWallet.PAYPAL;
    private String approvalUrl;
    private Instant approvalExpiresAt;
    private Money reservedBalance;
    private int changeSeq;
    private String createKey;
    /** When the latest known state of the current subscription happened (PayPal's clock, or Seed's own call). */
    private Instant subscriptionStateAt;
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
        this(
            id,
            memberId,
            name,
            targetAmount,
            monthlyAmount,
            targetDate,
            payPalSubscriptionId,
            status,
            currentBalance,
            monthlyAmount != null && monthlyAmount.cents() > 0 && currentBalance != null
                ? (int) (currentBalance.cents() / monthlyAmount.cents())
                : 0,
            createdAt,
            updatedAt
        );
    }

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
        int depositCount,
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
        this.depositCount = Math.max(0, depositCount);
        this.reservedBalance = Money.zero(monthlyAmount.currency());
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

    /**
     * A goal whose money will arrive through PayPal. A PayPal wallet waits for the member to approve the
     * subscription ({@code PENDING_APPROVAL}) and expires if that does not happen by {@code approvalExpiresAt};
     * a Venmo goal has no subscription (Venmo cannot fund one) and is active at once, fed by top-ups.
     */
    public static SavingsGoal createForSubscription(UUID id, String memberId, String name, Money targetAmount, Money monthlyAmount,
                                                    LocalDate targetDate, SavingsWallet wallet, String createKey,
                                                    Instant approvalExpiresAt, Instant now) {
        Objects.requireNonNull(wallet, "wallet");
        boolean needsApproval = wallet.supportsSubscriptions();
        SavingsGoal goal = new SavingsGoal(id, memberId, name, targetAmount, monthlyAmount, targetDate, null,
            needsApproval ? GoalStatus.PENDING_APPROVAL : GoalStatus.ACTIVE, Money.zero(monthlyAmount.currency()), 0, now, now);
        goal.wallet = wallet;
        goal.createKey = createKey;
        goal.approvalExpiresAt = needsApproval ? Objects.requireNonNull(approvalExpiresAt, "approvalExpiresAt") : null;
        return goal;
    }

    /** Restores the fields a stored goal has beyond the original constructor. */
    public SavingsGoal restoreState(SavingsWallet wallet, String approvalUrl, Instant approvalExpiresAt, Money reservedBalance,
                                    int changeSeq, String createKey) {
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.approvalUrl = approvalUrl;
        this.approvalExpiresAt = approvalExpiresAt;
        this.reservedBalance = Objects.requireNonNull(reservedBalance, "reservedBalance");
        this.changeSeq = changeSeq;
        this.createKey = createKey;
        return this;
    }

    public void attachSubscription(String subscriptionId, String approvalUrl, Instant now) {
        if (!Objects.equals(this.payPalSubscriptionId, subscriptionId)) {
            this.subscriptionStateAt = null; // the old subscription's history says nothing about the new one
        }
        this.payPalSubscriptionId = subscriptionId;
        this.approvalUrl = approvalUrl;
        this.updatedAt = now;
    }

    /** Restores when the latest known subscription state happened; null if none is known. */
    public SavingsGoal restoreSubscriptionStateAt(Instant subscriptionStateAt) {
        this.subscriptionStateAt = subscriptionStateAt;
        return this;
    }

    /**
     * True when a report about the current subscription happened before a state the goal already knows. PayPal
     * delivers webhooks out of order, so such a report is old news and must not change the goal. A report from
     * the same instant is not old.
     */
    public boolean subscriptionReportIsStale(Instant happenedAt) {
        return subscriptionStateAt != null && happenedAt != null && micros(happenedAt).isBefore(subscriptionStateAt);
    }

    /** Remembers when the latest subscription state happened; it never moves back. True if it moved forward. */
    public boolean noteSubscriptionState(Instant happenedAt) {
        if (happenedAt == null) {
            return false;
        }
        Instant at = micros(happenedAt);
        if (subscriptionStateAt != null && !at.isAfter(subscriptionStateAt)) {
            return false;
        }
        this.subscriptionStateAt = at;
        return true;
    }

    /** The database keeps microseconds; comparing at that precision keeps a stored instant equal to the one it came from. */
    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** PayPal says the member approved the subscription. A goal that is already past that point stays as it is. */
    public boolean activate(Instant now) {
        if (status != GoalStatus.PENDING_APPROVAL) {
            return false;
        }
        this.status = GoalStatus.ACTIVE;
        this.approvalUrl = null;
        this.approvalExpiresAt = null;
        this.updatedAt = now;
        return true;
    }

    /** True when the goal was waiting for approval and the window has closed. */
    public boolean approvalExpired(Instant now) {
        return status == GoalStatus.PENDING_APPROVAL && approvalExpiresAt != null && !now.isBefore(approvalExpiresAt);
    }

    public void expireApproval(Instant now) {
        if (status != GoalStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only a goal waiting for approval can expire; this one is " + status);
        }
        this.status = GoalStatus.EXPIRED;
        this.approvalUrl = null;
        this.updatedAt = now;
    }

    /** The member (or PayPal) ended the subscription: the goal stops saving but keeps its money. */
    public boolean pauseBecauseSubscriptionEnded(Instant now) {
        if (status != GoalStatus.ACTIVE) {
            return false; // paused, finished or never approved: nothing to stop
        }
        this.status = GoalStatus.PAUSED;
        this.updatedAt = now;
        return true;
    }

    public boolean acceptsDeposits() {
        return status != GoalStatus.CANCELLED && status != GoalStatus.PENDING_APPROVAL && status != GoalStatus.EXPIRED;
    }

    public int nextChange() {
        return ++changeSeq;
    }

    public void changeMonthlyAmount(Money newMonthly, Instant now) {
        this.monthlyAmount = validateMonthlyAmount(newMonthly);
        this.updatedAt = now;
    }

    /**
     * Moves money out of the balance into the reserve for a payout that has not finished. The balance check
     * and the move are one step, so the same money cannot be reserved twice.
     */
    public void reserve(Money amount, Instant now) {
        Objects.requireNonNull(amount, "Withdrawal amount is required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }
        if (status == GoalStatus.PENDING_APPROVAL || status == GoalStatus.EXPIRED) {
            throw new IllegalStateException("A goal that was never approved holds no money to withdraw");
        }
        if (amount.compareTo(this.currentBalance) > 0) {
            throw new IllegalArgumentException(
                "Insufficient savings balance: requested " + amount + " but available balance is " + this.currentBalance
            );
        }
        this.currentBalance = this.currentBalance.subtract(amount);
        this.reservedBalance = this.reservedBalance.add(amount);
        this.updatedAt = now;
        if (this.status == GoalStatus.COMPLETED && this.currentBalance.isLessThan(this.targetAmount)) {
            this.status = GoalStatus.ACTIVE;
        }
    }

    /** The payout reached the member: the reserve is spent. */
    public void settleReserve(Money amount, Instant now) {
        takeFromReserve(amount);
        this.updatedAt = now;
    }

    /** The payout failed or came back: the money is available in the goal again. */
    public void releaseReserve(Money amount, Instant now) {
        takeFromReserve(amount);
        giveBack(amount, now);
    }

    /** A payout that had reached the member was taken back by PayPal: the money is in the goal again. */
    public void restoreWithdrawn(Money amount, Instant now) {
        Objects.requireNonNull(amount, "amount");
        giveBack(amount, now);
    }

    private void takeFromReserve(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.compareTo(this.reservedBalance) > 0) {
            throw new IllegalStateException("Reserve holds " + this.reservedBalance + ", cannot release " + amount);
        }
        this.reservedBalance = this.reservedBalance.subtract(amount);
    }

    private void giveBack(Money amount, Instant now) {
        this.currentBalance = this.currentBalance.add(amount);
        this.updatedAt = now;
        if (this.currentBalance.isGreaterThanOrEqual(this.targetAmount) && this.status == GoalStatus.ACTIVE) {
            this.status = GoalStatus.COMPLETED;
        }
    }

    public void recordDeposit(Money depositAmount, String payPalCaptureId) {
        Objects.requireNonNull(depositAmount, "Deposit amount is required");
        if (!depositAmount.isPositive()) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        if (status == GoalStatus.CANCELLED) {
            throw new IllegalStateException("Cannot deposit into a cancelled savings goal");
        }
        if (status == GoalStatus.PENDING_APPROVAL || status == GoalStatus.EXPIRED) {
            throw new IllegalStateException("Cannot deposit into a savings goal whose PayPal approval is "
                + (status == GoalStatus.EXPIRED ? "expired" : "still pending"));
        }

        this.currentBalance = this.currentBalance.add(depositAmount);
        this.depositCount++;
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
        if (this.status == GoalStatus.CANCELLED || this.status == GoalStatus.EXPIRED) {
            throw new IllegalStateException("Cannot pause a goal that is " + this.status.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (this.status == GoalStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Approve the PayPal subscription before pausing this goal");
        }
        this.status = GoalStatus.PAUSED;
        this.updatedAt = Instant.now();
    }

    public void resume() {
        if (this.status == GoalStatus.CANCELLED || this.status == GoalStatus.EXPIRED) {
            throw new IllegalStateException("Cannot resume a goal that is " + this.status.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (this.status == GoalStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Approve the PayPal subscription before resuming this goal");
        }
        this.status = GoalStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = GoalStatus.CANCELLED;
        this.approvalUrl = null;
        this.updatedAt = Instant.now();
    }

    /** Returns a paused goal that needs a new subscription to the approval step (its old one ended at PayPal). */
    public void requireNewApproval(Instant approvalExpiresAt, Instant now) {
        if (status != GoalStatus.PAUSED) {
            throw new IllegalStateException("Only a paused goal can start a new approval; this one is " + status);
        }
        this.status = GoalStatus.PENDING_APPROVAL;
        this.approvalExpiresAt = Objects.requireNonNull(approvalExpiresAt, "approvalExpiresAt");
        this.updatedAt = now;
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
    public int getDepositCount() { return depositCount; }
    public SavingsWallet getWallet() { return wallet; }
    public String getApprovalUrl() { return approvalUrl; }
    public Instant getApprovalExpiresAt() { return approvalExpiresAt; }
    public Money getReservedBalance() { return reservedBalance; }
    public int getChangeSeq() { return changeSeq; }
    public String getCreateKey() { return createKey; }
    public Instant getSubscriptionStateAt() { return subscriptionStateAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setPayPalSubscriptionId(String payPalSubscriptionId) {
        this.payPalSubscriptionId = payPalSubscriptionId;
        this.updatedAt = Instant.now();
    }
}
