package com.paypalseed.fraud.domain.rules;

import java.util.List;

/**
 * The rules in force. The version names the exact set, so a flag can always be traced to the rules it was raised under.
 * Changing a threshold or a weight is a new version.
 */
public final class RuleSet {

    public static final String VERSION = "fraud-rules-v1";

    private RuleSet() {
    }

    public static List<Rule> v1() {
        return List.of(VelocityRule.goals(), VelocityRule.applications(), new SharedPayerRule(), new DepositChurnRule(),
                new NewAccountLoanRule(), VelocityRule.failedPayments(), new RecipientChangeRule());
    }
}
