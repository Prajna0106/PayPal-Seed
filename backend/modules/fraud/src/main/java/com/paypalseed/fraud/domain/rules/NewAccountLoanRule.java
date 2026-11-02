package com.paypalseed.fraud.domain.rules;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Fact;
import com.paypalseed.fraud.domain.model.Signal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A loan application or disbursement within 72 hours of the account appearing. The account's start is its registration
 * when the platform reported one, otherwise the first fact Seed saw for the member; an application that is itself the first
 * thing seen proves nothing about age, so the rule stays silent then.
 */
public final class NewAccountLoanRule implements Rule {

    public static final String CODE = "NEW_ACCOUNT_LOAN";
    static final Duration WINDOW = Duration.ofHours(72);
    private static final int WEIGHT = 35;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<Signal> evaluate(RuleContext context) {
        List<Fact> loanFacts = context.of(FactKind.APPLICATION_SUBMITTED, FactKind.LOAN_DISBURSED);
        if (loanFacts.isEmpty()) {
            return Optional.empty();
        }
        Optional<Fact> registered = context.of(FactKind.ACCOUNT_CREATED).stream().findFirst();
        Optional<Fact> start = registered.isPresent() ? registered
                : context.facts().stream()
                        .filter(f -> f.kind() != FactKind.APPLICATION_SUBMITTED && f.kind() != FactKind.LOAN_DISBURSED)
                        .findFirst();
        if (start.isEmpty()) {
            return Optional.empty();
        }
        Fact created = start.get();
        Optional<Fact> trigger = loanFacts.stream().filter(f -> !f.occurredAt().isBefore(created.occurredAt())).findFirst();
        if (trigger.isEmpty()) {
            return Optional.empty();
        }
        Duration age = Duration.between(created.occurredAt(), trigger.get().occurredAt());
        if (age.compareTo(WINDOW) > 0) {
            return Optional.empty();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("accountAgeHours", age.toHours());
        evidence.put("windowHours", WINDOW.toHours());
        evidence.put("accountStartBasis", registered.isPresent() ? "REGISTERED" : "FIRST_SEEN");
        evidence.put("loanFact", trigger.get().kind().name());
        return Optional.of(new Signal(CODE, WEIGHT,
                "A loan " + (trigger.get().kind() == FactKind.APPLICATION_SUBMITTED ? "application" : "disbursement")
                        + " came " + age.toHours() + " hours after the account started (the usual minimum is "
                        + WINDOW.toHours() + ")", evidence, List.of(created.id(), trigger.get().id()), context.asOf()));
    }
}
