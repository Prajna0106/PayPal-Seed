package com.paypalseed.fraud.domain.rules;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Fact;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a rule may look at: the member's own facts, and for each hashed reference the member used, how many other members
 * used it too. {@code asOf} stamps the signal; it is never used to decide whether a rule fires.
 */
public record RuleContext(UUID memberId, Instant asOf, List<Fact> facts, Map<String, Integer> otherMembersByRef) {

    private static final Comparator<Fact> BY_TIME = Comparator.comparing(Fact::occurredAt).thenComparingLong(Fact::id);

    public RuleContext {
        facts = facts.stream().sorted(BY_TIME).toList();
        otherMembersByRef = Map.copyOf(otherMembersByRef);
    }

    public List<Fact> of(FactKind... kinds) {
        List<FactKind> wanted = List.of(kinds);
        return facts.stream().filter(f -> wanted.contains(f.kind())).toList();
    }
}
