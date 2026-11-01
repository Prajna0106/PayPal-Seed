package com.paypalseed.fraud.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Signal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DepositChurnRuleTest {

    private final DepositChurnRule rule = new DepositChurnRule();

    @Test
    void moneyOutWithinTwoDaysOfMoneyInFires() {
        Scenario s = new Scenario();
        long in = s.add(FactKind.DEPOSIT, Duration.ZERO, 10_000L);
        long out = s.add(FactKind.WITHDRAWAL, Duration.ofHours(2), 10_000L);
        Signal signal = rule.evaluate(s.context()).orElseThrow();
        assertThat(signal.code()).isEqualTo("DEPOSIT_CHURN");
        assertThat(signal.weight()).isEqualTo(15);
        assertThat(signal.factIds()).containsExactlyInAnyOrder(in, out);
        assertThat(signal.evidence()).containsEntry("roundTrips", 1).containsEntry("withdrawnCents", 10_000L)
                .containsEntry("depositedWithinWindowCents", 10_000L);
    }

    @Test
    void aRepeatIsWorthMore() {
        Scenario s = new Scenario();
        s.add(FactKind.DEPOSIT, Duration.ZERO, 10_000L);
        s.add(FactKind.WITHDRAWAL, Duration.ofHours(2), 10_000L);
        s.add(FactKind.DEPOSIT, Duration.ofDays(5), 5_000L);
        s.add(FactKind.WITHDRAWAL, Duration.ofDays(5).plusHours(1), 5_000L);
        Signal signal = rule.evaluate(s.context()).orElseThrow();
        assertThat(signal.weight()).isEqualTo(35);
        assertThat(signal.evidence()).containsEntry("roundTrips", 2);
    }

    @Test
    void savingForAWhileAndThenWithdrawingIsNormal() {
        Scenario s = new Scenario();
        s.add(FactKind.DEPOSIT, Duration.ZERO, 10_000L);
        s.add(FactKind.DEPOSIT, Duration.ofDays(30), 10_000L);
        s.add(FactKind.WITHDRAWAL, Duration.ofDays(60), 20_000L);
        assertThat(rule.evaluate(s.context())).isEmpty();

        Scenario justOutside = new Scenario();
        justOutside.add(FactKind.DEPOSIT, Duration.ZERO, 10_000L);
        justOutside.add(FactKind.WITHDRAWAL, Duration.ofHours(48).plusSeconds(1), 10_000L);
        assertThat(rule.evaluate(justOutside.context())).isEmpty();

        Scenario justInside = new Scenario();
        justInside.add(FactKind.DEPOSIT, Duration.ZERO, 10_000L);
        justInside.add(FactKind.WITHDRAWAL, Duration.ofHours(48), 10_000L);
        assertThat(rule.evaluate(justInside.context())).isPresent();
    }

    @Test
    void smallAmountsAreIgnored() {
        Scenario s = new Scenario();
        s.add(FactKind.DEPOSIT, Duration.ZERO, 2_499L);
        s.add(FactKind.WITHDRAWAL, Duration.ofMinutes(5), 2_499L);
        assertThat(rule.evaluate(s.context())).isEmpty();

        Scenario atMinimum = new Scenario();
        atMinimum.add(FactKind.DEPOSIT, Duration.ZERO, 2_500L);
        atMinimum.add(FactKind.WITHDRAWAL, Duration.ofMinutes(5), 2_500L);
        assertThat(rule.evaluate(atMinimum.context())).isPresent();
    }

    @Test
    void mostlyOldMoneyLeavingIsNotChurn() {
        Scenario s = new Scenario();
        s.add(FactKind.DEPOSIT, Duration.ZERO, 50_000L);
        s.add(FactKind.DEPOSIT, Duration.ofDays(20), 3_000L);
        s.add(FactKind.WITHDRAWAL, Duration.ofDays(20).plusHours(1), 10_000L); // 30% is recent
        assertThat(rule.evaluate(s.context())).isEmpty();
    }

    @Test
    void eightyPercentRecentIsEnough() {
        Scenario s = new Scenario();
        s.add(FactKind.DEPOSIT, Duration.ZERO, 8_000L);
        s.add(FactKind.WITHDRAWAL, Duration.ofHours(1), 10_000L);
        assertThat(rule.evaluate(s.context())).isPresent();

        Scenario below = new Scenario();
        below.add(FactKind.DEPOSIT, Duration.ZERO, 7_999L);
        below.add(FactKind.WITHDRAWAL, Duration.ofHours(1), 10_000L);
        assertThat(rule.evaluate(below.context())).isEmpty();
    }

    @Test
    void oneDepositCannotExplainTwoWithdrawals() {
        Scenario s = new Scenario();
        s.add(FactKind.DEPOSIT, Duration.ZERO, 10_000L);
        s.add(FactKind.WITHDRAWAL, Duration.ofHours(1), 6_000L);
        s.add(FactKind.WITHDRAWAL, Duration.ofHours(2), 6_000L); // only 4,000 of it is left to match: 66%
        Signal signal = rule.evaluate(s.context()).orElseThrow();
        assertThat(signal.evidence()).containsEntry("roundTrips", 1);
    }

    @Test
    void arrivalOrderBreaksTiesBetweenFactsAtTheSameInstant() {
        Scenario depositFirst = new Scenario();
        depositFirst.add(FactKind.DEPOSIT, Duration.ZERO, 10_000L);
        depositFirst.add(FactKind.WITHDRAWAL, Duration.ZERO, 10_000L);
        assertThat(rule.evaluate(depositFirst.context())).isPresent();

        Scenario withdrawalFirst = new Scenario();
        withdrawalFirst.add(FactKind.WITHDRAWAL, Duration.ZERO, 10_000L);
        withdrawalFirst.add(FactKind.DEPOSIT, Duration.ZERO, 10_000L);
        assertThat(rule.evaluate(withdrawalFirst.context())).isEmpty();
        assertThat(rule.evaluate(depositFirst.reversed())).isEqualTo(rule.evaluate(depositFirst.context()));
    }

    @Test
    void noWithdrawalsNoSignal() {
        Scenario s = new Scenario();
        s.add(FactKind.DEPOSIT, Duration.ZERO, 10_000L);
        s.add(FactKind.DEPOSIT, Duration.ofDays(1), 10_000L);
        assertThat(rule.evaluate(s.context())).isEmpty();
        assertThat(rule.evaluate(new Scenario().context())).isEmpty();
    }
}
