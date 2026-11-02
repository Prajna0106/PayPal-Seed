package com.paypalseed.fraud.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Signal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SharedPayerRuleTest {

    private final SharedPayerRule rule = new SharedPayerRule();

    @Test
    void aPayerThatPaysOnlyForThisMemberIsNothing() {
        Scenario s = new Scenario(Map.of());
        s.add(FactKind.PAYER_SEEN, Duration.ZERO, null, Scenario.hash('a'));
        assertThat(rule.evaluate(s.context())).isEmpty();

        Scenario zeroOthers = new Scenario(Map.of(Scenario.hash('a'), 0));
        zeroOthers.add(FactKind.PAYER_SEEN, Duration.ZERO, null, Scenario.hash('a'));
        assertThat(rule.evaluate(zeroOthers.context())).isEmpty();
    }

    @Test
    void aPayerSharedWithAnotherMemberFiresAndShowsOnlyAShortTag() {
        Scenario s = new Scenario(Map.of(Scenario.hash('a'), 1));
        long fact = s.add(FactKind.PAYER_SEEN, Duration.ZERO, null, Scenario.hash('a'));
        Signal signal = rule.evaluate(s.context()).orElseThrow();
        assertThat(signal.code()).isEqualTo("SHARED_PAYER");
        assertThat(signal.weight()).isEqualTo(40);
        assertThat(signal.factIds()).containsExactly(fact);
        assertThat(signal.evidence()).containsEntry("otherMembersOnSamePayer", 1).containsEntry("sharedPayers", 1);
        assertThat(signal.evidence().get("payerTags")).isEqualTo(java.util.List.of("aaaaaaaa"));
        assertThat(signal.evidence().toString()).doesNotContain(Scenario.hash('a'));
        assertThat(signal.explanation()).endsWith("1 other member").doesNotContain(Scenario.hash('a'));
    }

    @Test
    void theExplanationCountsMembers() {
        Scenario s = new Scenario(Map.of(Scenario.hash('b'), 3, Scenario.hash('c'), 1));
        s.add(FactKind.PAYER_SEEN, Duration.ZERO, null, Scenario.hash('b'));
        s.add(FactKind.PAYER_SEEN, Duration.ofDays(1), null, Scenario.hash('c'));
        s.add(FactKind.PAYER_SEEN, Duration.ofDays(2), null, Scenario.hash('d'));
        Signal signal = rule.evaluate(s.context()).orElseThrow();
        assertThat(signal.evidence()).containsEntry("sharedPayers", 2).containsEntry("otherMembersOnSamePayer", 3);
        assertThat(signal.factIds()).hasSize(2);
        assertThat(signal.explanation()).endsWith("3 other members");
    }

    @Test
    void factsWithoutAReferenceAreIgnored() {
        Scenario s = new Scenario(Map.of(Scenario.hash('a'), 5));
        s.add(FactKind.PAYER_SEEN, Duration.ZERO, null, null);
        s.add(FactKind.DEPOSIT, Duration.ZERO, 100L, Scenario.hash('a'));
        assertThat(rule.evaluate(s.context())).isEmpty();
    }
}
