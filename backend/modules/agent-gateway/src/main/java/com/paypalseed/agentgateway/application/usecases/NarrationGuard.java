package com.paypalseed.agentgateway.application.usecases;

import java.util.regex.Pattern;

/**
 * The deterministic content rules every model-written narration must pass before a member or officer sees it
 * (ADR 0009). Shared by the Coach and Underwriting narrators so both reject the same things:
 * credit-score and fee talk, promises of approval, forgiveness or waivers, links and email addresses,
 * "skip the review" language, and any leak of the prompt's own fence tags.
 *
 * <p>A violation never reaches a person: the caller falls back to its deterministic template and records a
 * grounding failure against the kill switch.
 */
final class NarrationGuard {

    private static final Pattern FORBIDDEN = Pattern.compile(
        "\\b(?:fico|credit\\s+scores?|credit\\s+bureaus?|late\\s+fees?|penalt(?:y|ies)|guarantee[ds]?"
            + "|guaranteed\\s+approval|apr|approved|forgiven|waived|perdonad[oa]|system\\s+prompt"
            + "|computed_(?:facts|plan|trust_facts|portfolio_metrics)|tool_result|member_text|member_dispute_note|goal_name)\\b"
            + "|https?://|www\\.|[\\w.+-]+@[\\w-]+\\.[\\w.]+"
            + "|without\\s+(?:a\\s+|an\\s+|the\\s+)?(?:officer\\s+|second\\s+)?(?:review|check)",
        Pattern.CASE_INSENSITIVE);

    /**
     * A dollar figure as the prompts write it: "$35,000" or "$1,200.50". Thousands groups must be exactly three digits, so
     * the comma in "$35,000, 2.05x coverage" is punctuation, not part of the figure.
     */
    static final Pattern DOLLAR_AMOUNT = Pattern.compile("\\$[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?");

    private NarrationGuard() {
    }

    /** True when the text contains anything a narration must never say. */
    static boolean violates(String text) {
        return text != null && FORBIDDEN.matcher(text).find();
    }
}
