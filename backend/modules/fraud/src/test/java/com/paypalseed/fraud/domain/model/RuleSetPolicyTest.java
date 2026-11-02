package com.paypalseed.fraud.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.fraud.domain.rules.Rule;
import com.paypalseed.fraud.domain.rules.RuleSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rules as a whole: stable codes, and weights that keep a struggling-but-honest member from being flagged. */
class RuleSetPolicyTest {

    private static final int FLAG_THRESHOLD = 40;

    @Test
    void everyRuleHasAUniqueCodeThatTheContractNames() {
        List<String> codes = RuleSet.v1().stream().map(Rule::code).toList();
        assertThat(codes).doesNotHaveDuplicates().containsExactlyInAnyOrder("GOAL_VELOCITY", "APPLICATION_VELOCITY",
                "SHARED_PAYER", "DEPOSIT_CHURN", "NEW_ACCOUNT_LOAN", "REPEATED_FAILED_PAYMENTS", "PAYOUT_RECIPIENT_CHANGE");
        assertThat(RuleSet.VERSION).isEqualTo("fraud-rules-v1");
    }

    @Test
    void failedPaymentsAloneNeverFlagAMember() {
        assertThat(FlagScore.of(List.of(signal("REPEATED_FAILED_PAYMENTS", 10)))).isLessThan(FLAG_THRESHOLD);
    }

    @Test
    void aSingleWeakSignalOrTwoMildOnesDoNotFlag() {
        assertThat(FlagScore.of(List.of(signal("GOAL_VELOCITY", 20), signal("DEPOSIT_CHURN", 15)))).isLessThan(FLAG_THRESHOLD);
        assertThat(FlagScore.of(List.of(signal("GOAL_VELOCITY", 20), signal("REPEATED_FAILED_PAYMENTS", 10)))).isLessThan(FLAG_THRESHOLD);
    }

    @Test
    void oneStrongSignalOrSeveralMildOnesDoFlag() {
        assertThat(FlagScore.of(List.of(signal("SHARED_PAYER", 40)))).isGreaterThanOrEqualTo(FLAG_THRESHOLD);
        assertThat(FlagScore.of(List.of(signal("PAYOUT_RECIPIENT_CHANGE", 40)))).isGreaterThanOrEqualTo(FLAG_THRESHOLD);
        assertThat(FlagScore.of(List.of(signal("GOAL_VELOCITY", 20), signal("DEPOSIT_CHURN", 15),
                signal("REPEATED_FAILED_PAYMENTS", 10)))).isGreaterThanOrEqualTo(FLAG_THRESHOLD);
    }

    @Test
    void aRuleThatKeepsFiringCountsOnceAtItsHeaviest() {
        assertThat(FlagScore.of(List.of(signal("DEPOSIT_CHURN", 15), signal("DEPOSIT_CHURN", 35), signal("DEPOSIT_CHURN", 15))))
                .isEqualTo(35);
        assertThat(FlagScore.of(List.of())).isZero();
    }

    @Test
    void theFingerprintIsStableAndSensitiveToEvidenceAndFacts() {
        Signal a = new Signal("GOAL_VELOCITY", 20, "x", Map.of("count", 4, "firstAt", "t1"), List.of(3L, 1L, 2L), Instant.EPOCH);
        Signal sameButReordered = new Signal("GOAL_VELOCITY", 20, "other words", Map.of("firstAt", "t1", "count", 4),
                List.of(1L, 2L, 3L), Instant.now());
        Signal moreEvidence = new Signal("GOAL_VELOCITY", 20, "x", Map.of("count", 5, "firstAt", "t1"), List.of(1L, 2L, 3L), Instant.EPOCH);
        Signal otherFacts = new Signal("GOAL_VELOCITY", 20, "x", Map.of("count", 4, "firstAt", "t1"), List.of(1L, 2L, 4L), Instant.EPOCH);
        Signal otherRule = new Signal("APPLICATION_VELOCITY", 20, "x", Map.of("count", 4, "firstAt", "t1"), List.of(1L, 2L, 3L), Instant.EPOCH);
        assertThat(a.fingerprint()).hasSize(64).isEqualTo(sameButReordered.fingerprint())
                .isNotEqualTo(moreEvidence.fingerprint()).isNotEqualTo(otherFacts.fingerprint()).isNotEqualTo(otherRule.fingerprint());
    }

    private static Signal signal(String code, int weight) {
        return new Signal(code, weight, "test", Map.of(), List.of(), Instant.EPOCH);
    }
}
