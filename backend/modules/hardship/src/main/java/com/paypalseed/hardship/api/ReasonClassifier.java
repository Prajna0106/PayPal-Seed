package com.paypalseed.hardship.api;

/**
 * Turns a member's free-text reason into one closed-set category. Hardship never puts the text in a
 * prompt, an event or a log: it hands it to this port and keeps only the category.
 *
 * <p>Implemented outside hardship (the Guardian module provides a model-backed one), so hardship has no
 * dependency on the agent gateway. Hardship falls back to its own keyword classifier when no
 * implementation is registered or the call fails.
 */
public interface ReasonClassifier {

    /** Categories match {@code contracts/openapi/agent-tools.yaml}. */
    enum Category {
        INCOME_REDUCED,
        JOB_LOSS,
        MEDICAL,
        FAMILY_EMERGENCY,
        UNEXPECTED_EXPENSE,
        TIMING_ONLY,
        OTHER
    }

    record Classification(Category category, int confidencePct, String method) {
    }

    Classification classify(String memberText, String locale);
}
