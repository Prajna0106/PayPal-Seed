package com.paypalseed.savings;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsGoal;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SavingsGoalTest {

    @Test
    void rejectsMonthlyDepositBelowFiveDollars() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            SavingsGoal.create(
                "payer-123",
                "Mixer Goal",
                Money.usd(500.00),
                Money.usd(4.99), // below $5 minimum
                LocalDate.now().plusMonths(6),
                "sub-123"
            )
        );

        assertTrue(ex.getMessage().contains("Minimum monthly savings amount is $5.00"));
    }

    @Test
    void successfullyCreatesValidGoal() {
        SavingsGoal goal = SavingsGoal.create(
            "payer-123",
            "Emergency Fund",
            Money.usd(600.00),
            Money.usd(50.00),
            LocalDate.now().plusMonths(12),
            "sub-999"
        );

        assertNotNull(goal.getId());
        assertEquals("Emergency Fund", goal.getName());
        assertEquals(GoalStatus.ACTIVE, goal.getStatus());
        assertEquals(Money.zeroUsd(), goal.getCurrentBalance());
    }

    @Test
    void recordsDepositsAndMarksCompletedWhenTargetMet() {
        SavingsGoal goal = SavingsGoal.create(
            "payer-123",
            "Baking Mixer",
            Money.usd(150.00),
            Money.usd(75.00),
            LocalDate.now().plusMonths(2),
            "sub-123"
        );

        goal.recordDeposit(Money.usd(75.00), "capture-1");
        assertEquals(Money.usd(75.00), goal.getCurrentBalance());
        assertEquals(GoalStatus.ACTIVE, goal.getStatus());

        goal.recordDeposit(Money.usd(75.00), "capture-2");
        assertEquals(Money.usd(150.00), goal.getCurrentBalance());
        assertEquals(GoalStatus.COMPLETED, goal.getStatus());
    }

    @Test
    void handlesWithdrawalsCorrectly() {
        SavingsGoal goal = SavingsGoal.create(
            "payer-123",
            "Vacation",
            Money.usd(1000.00),
            Money.usd(100.00),
            null,
            "sub-100"
        );

        goal.recordDeposit(Money.usd(200.00), "cap-1");
        goal.recordWithdrawal(Money.usd(50.00));

        assertEquals(Money.usd(150.00), goal.getCurrentBalance());
    }

    @Test
    void rejectsWithdrawalExceedingCurrentBalance() {
        SavingsGoal goal = SavingsGoal.create(
            "payer-123",
            "Vacation",
            Money.usd(1000.00),
            Money.usd(100.00),
            null,
            "sub-100"
        );

        goal.recordDeposit(Money.usd(50.00), "cap-1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            goal.recordWithdrawal(Money.usd(50.01))
        );

        assertTrue(ex.getMessage().contains("Insufficient savings balance"));
    }

    @Test
    void allowsPauseAndResumeWithoutPenalty() {
        SavingsGoal goal = SavingsGoal.create(
            "payer-123",
            "Laptop",
            Money.usd(800.00),
            Money.usd(100.00),
            null,
            "sub-200"
        );

        goal.pause();
        assertEquals(GoalStatus.PAUSED, goal.getStatus());

        goal.resume();
        assertEquals(GoalStatus.ACTIVE, goal.getStatus());
    }
}
