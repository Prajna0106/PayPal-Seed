package com.paypalseed.fraud.domain.rules;

import com.paypalseed.fraud.domain.model.Signal;
import java.util.Optional;

/**
 * One fraud rule: a pure function from a member's facts to at most one signal. No clock, no database, no randomness, no
 * model: the same facts always give the same answer, and an officer can read the rule and redo it by hand.
 */
public interface Rule {

    String code();

    Optional<Signal> evaluate(RuleContext context);
}
