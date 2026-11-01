package com.paypalseed.agentgateway.api;

import java.util.List;
import java.util.UUID;

/**
 * Turns untrusted member text into one closed-set category (04 §7.5). The model gets no tools and only
 * the text; any answer outside the set becomes {@code OTHER}. The category is a label for officers: it
 * never changes a score, an option or an approval, and the text is never logged or stored.
 */
public interface ReasonExtractor {

    List<String> CATEGORIES = List.of("INCOME_REDUCED", "JOB_LOSS", "MEDICAL", "FAMILY_EMERGENCY", "UNEXPECTED_EXPENSE",
            "TIMING_ONLY", "OTHER");

    /** {@code method}: MODEL (validated model answer), KEYWORDS (model unavailable) or DEFAULT (model answer invalid). */
    record Extraction(String category, int confidencePct, String method) {

        public boolean usedFallback() {
            return !"MODEL".equals(method);
        }
    }

    Extraction extract(UUID caseId, String memberText, String locale);

    default Extraction extract(String memberText, String locale) {
        return extract(null, memberText, locale);
    }
}
