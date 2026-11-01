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
            + "|computed_(?:facts|plan)|tool_result|member_text|member_dispute_note|goal_name)\\b"
            + "|https?://|www\\.|[\\w.+-]+@[\\w-]+\\.[\\w.]+"
            + "|without\\s+(?:a\\s+|an\\s+|the\\s+)?(?:officer\\s+|second\\s+)?(?:review|check)",
        Pattern.CASE_INSENSITIVE);

    private NarrationGuard() {
    }

    /** True when the text contains anything a narration must never say. */
    static boolean violates(String text) {
        return text != null && FORBIDDEN.matcher(text).find();
    }
}
