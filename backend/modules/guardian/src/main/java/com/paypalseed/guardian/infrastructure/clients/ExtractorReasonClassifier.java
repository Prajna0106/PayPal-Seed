package com.paypalseed.guardian.infrastructure.clients;

import com.paypalseed.agentgateway.api.ReasonExtractor;
import com.paypalseed.agentgateway.api.ReasonExtractor.Extraction;
import com.paypalseed.hardship.api.ReasonClassifier;
import org.springframework.stereotype.Component;

/**
 * Hardship's reason classifier, backed by the gateway's tool-less extractor. The member's text goes to the
 * extractor and nowhere else; only the closed-set category comes back.
 */
@Component
class ExtractorReasonClassifier implements ReasonClassifier {

    private final ReasonExtractor extractor;

    ExtractorReasonClassifier(ReasonExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public Classification classify(String memberText, String locale) {
        Extraction extraction = extractor.extract(memberText, locale);
        return new Classification(category(extraction.category()), extraction.confidencePct(), extraction.method());
    }

    private static Category category(String name) {
        try {
            return Category.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Category.OTHER;
        }
    }
}
