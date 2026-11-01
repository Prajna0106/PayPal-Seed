package com.paypalseed.fraud.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Signal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class VelocityRuleTest {

    private final VelocityRule goals = VelocityRule.goals();

    @Test
    void threeGoalsInADayIsNormal() {
        Scenario s = new Scenario();
        s.add(FactKind.GOAL_CREATED, Duration.ZERO);
        s.add(FactKind.GOAL_CREATED, Duration.ofHours(2));
        s.add(FactKind.GOAL_CREATED, Duration.ofHours(23));
        assertThat(goals.evaluate(s.context())).isEmpty();
    }

    @Test
    void fourGoalsInADayFiresWithTheFactsBehindIt() {
        Scenario s = new Scenario();
        long a = s.add(FactKind.GOAL_CREATED, Duration.ZERO);
        long b = s.add(FactKind.GOAL_CREATED, Duration.ofHours(1));
        long c = s.add(FactKind.GOAL_CREATED, Duration.ofHours(2));
        long d = s.add(FactKind.GOAL_CREATED, Duration.ofHours(3));
        Signal signal = goals.evaluate(s.context()).orElseThrow();
        assertThat(signal.code()).isEqualTo("GOAL_VELOCITY");
        assertThat(signal.weight()).isEqualTo(20);
        assertThat(signal.factIds()).containsExactly(a, b, c, d);
        assertThat(signal.evidence()).containsEntry("count", 4).containsEntry("threshold", 4).containsEntry("windowHours", 24L);
        assertThat(signal.explanation()).contains("4 new savings goals within 24 hours");
    }

    @Test
    void theWindowBoundaryIsInclusive() {
        Scenario exactly = new Scenario();
        for (int i = 0; i < 4; i++) {
            exactly.add(FactKind.GOAL_CREATED, Duration.ofHours(8L * i)); // 0, 8, 16, 24
        }
        assertThat(goals.evaluate(exactly.context())).isPresent();

        Scenario justOver = new Scenario();
        for (int i = 0; i < 3; i++) {
            justOver.add(FactKind.GOAL_CREATED, Duration.ofHours(8L * i));
        }
        justOver.add(FactKind.GOAL_CREATED, Duration.ofHours(24).plusSeconds(1));
        assertThat(goals.evaluate(justOver.context())).isEmpty();
    }

    @Test
    void goalsSpreadOverWeeksNeverFire() {
        Scenario s = new Scenario();
        for (int i = 0; i < 10; i++) {
            s.add(FactKind.GOAL_CREATED, Duration.ofDays(3L * i));
        }
        assertThat(goals.evaluate(s.context())).isEmpty();
    }

    @Test
    void theBusiestWindowIsFoundAmongSeveralBursts() {
        Scenario s = new Scenario();
        for (int i = 0; i < 3; i++) {
            s.add(FactKind.GOAL_CREATED, Duration.ofMinutes(i));
        }
        List<Long> burst = List.of(s.add(FactKind.GOAL_CREATED, Duration.ofDays(10)), s.add(FactKind.GOAL_CREATED, Duration.ofDays(10).plusMinutes(5)),
                s.add(FactKind.GOAL_CREATED, Duration.ofDays(10).plusMinutes(9)), s.add(FactKind.GOAL_CREATED, Duration.ofDays(10).plusMinutes(30)),
                s.add(FactKind.GOAL_CREATED, Duration.ofDays(10).plusMinutes(31)));
        Signal signal = goals.evaluate(s.context()).orElseThrow();
        assertThat(signal.factIds()).containsExactlyElementsOf(burst);
        assertThat(signal.evidence()).containsEntry("count", 5);
    }

    @Test
    void otherKindsDoNotCountAndInputOrderDoesNotMatter() {
        Scenario s = new Scenario();
        for (int i = 0; i < 6; i++) {
            s.add(FactKind.DEPOSIT, Duration.ofMinutes(i), 1_000L);
        }
        for (int i = 0; i < 3; i++) {
            s.add(FactKind.GOAL_CREATED, Duration.ofMinutes(i));
        }
        assertThat(goals.evaluate(s.context())).isEmpty();
        s.add(FactKind.GOAL_CREATED, Duration.ofMinutes(10));
        assertThat(goals.evaluate(s.context())).isPresent();
        assertThat(goals.evaluate(s.reversed())).isEqualTo(goals.evaluate(s.context()));
    }

    @Test
    void twoApplicationsInAWeekFires() {
        VelocityRule rule = VelocityRule.applications();
        Scenario one = new Scenario();
        one.add(FactKind.APPLICATION_SUBMITTED, Duration.ZERO);
        assertThat(rule.evaluate(one.context())).isEmpty();

        Scenario two = new Scenario();
        two.add(FactKind.APPLICATION_SUBMITTED, Duration.ZERO);
        two.add(FactKind.APPLICATION_SUBMITTED, Duration.ofDays(6));
        Signal signal = rule.evaluate(two.context()).orElseThrow();
        assertThat(signal.code()).isEqualTo("APPLICATION_VELOCITY");
        assertThat(signal.weight()).isEqualTo(30);
        assertThat(signal.explanation()).contains("2 loan applications within 7 days");

        Scenario apart = new Scenario();
        apart.add(FactKind.APPLICATION_SUBMITTED, Duration.ZERO);
        apart.add(FactKind.APPLICATION_SUBMITTED, Duration.ofDays(8));
        assertThat(rule.evaluate(apart.context())).isEmpty();
    }

    @Test
    void failedPaymentsWeighLittleOnPurpose() {
        VelocityRule rule = VelocityRule.failedPayments();
        Scenario s = new Scenario();
        s.add(FactKind.PAYMENT_FAILED, Duration.ZERO);
        s.add(FactKind.PAYMENT_FAILED, Duration.ofDays(10));
        assertThat(rule.evaluate(s.context())).isEmpty();
        s.add(FactKind.PAYMENT_FAILED, Duration.ofDays(29));
        Signal signal = rule.evaluate(s.context()).orElseThrow();
        assertThat(signal.code()).isEqualTo("REPEATED_FAILED_PAYMENTS");
        assertThat(signal.weight()).isEqualTo(10).isLessThan(40);
    }

    @Test
    void sameFactsGiveTheSameFingerprintAndMoreFactsGiveAnother() {
        Scenario s = new Scenario();
        for (int i = 0; i < 4; i++) {
            s.add(FactKind.GOAL_CREATED, Duration.ofMinutes(i));
        }
        Signal first = goals.evaluate(s.context()).orElseThrow();
        assertThat(goals.evaluate(s.context()).orElseThrow().fingerprint()).isEqualTo(first.fingerprint());
        s.add(FactKind.GOAL_CREATED, Duration.ofMinutes(5));
        assertThat(goals.evaluate(s.context()).orElseThrow().fingerprint()).isNotEqualTo(first.fingerprint());
    }

    @Test
    void aRuleNeedsSaneParameters() {
        assertThatThrownBy(() -> new VelocityRule("X_RULE", FactKind.GOAL_CREATED, Duration.ofHours(1), 1, 5, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VelocityRule("X_RULE", FactKind.GOAL_CREATED, Duration.ZERO, 3, 5, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VelocityRule("X_RULE", FactKind.GOAL_CREATED, Duration.ofHours(1), 3, 0, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
