package com.paypalseed.fraud.domain.rules;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Fact;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds the facts of one member for a rule test, with ids in the order the facts are added. */
final class Scenario {

    static final Instant START = Instant.parse("2026-11-01T12:00:00Z");
    static final Instant AS_OF = Instant.parse("2026-12-01T12:00:00Z");

    private final UUID member = UUID.randomUUID();
    private final List<Fact> facts = new ArrayList<>();
    private final Map<String, Integer> others;
    private long nextId = 1;

    Scenario() {
        this(Map.of());
    }

    Scenario(Map<String, Integer> others) {
        this.others = others;
    }

    /** Adds a fact {@code after} the scenario start and returns its id. */
    long add(FactKind kind, Duration after) {
        return add(kind, after, null, null);
    }

    long add(FactKind kind, Duration after, Long amountCents) {
        return add(kind, after, amountCents, null);
    }

    long add(FactKind kind, Duration after, Long amountCents, String refHash) {
        long id = nextId++;
        facts.add(new Fact(id, member, kind, START.plus(after), amountCents, refHash, Map.of()));
        return id;
    }

    RuleContext context() {
        return new RuleContext(member, AS_OF, facts, others);
    }

    /** The same facts in reverse order, to show that a rule reads time and not list position. */
    RuleContext reversed() {
        List<Fact> copy = new ArrayList<>(facts);
        java.util.Collections.reverse(copy);
        return new RuleContext(member, AS_OF, copy, others);
    }

    static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }
}
