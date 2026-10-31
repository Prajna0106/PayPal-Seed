package com.paypalseed.hardship.application.usecases;

import com.paypalseed.hardship.api.ReasonClassifier;
import com.paypalseed.hardship.api.ReasonClassifier.Category;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Reduces a member's own words to one closed category. A registered {@link ReasonClassifier} (the Guardian's
 * model-backed one) is tried first; any failure, or an unknown answer, falls back to keywords. The text is never
 * stored, logged or put in an event.
 */
@Component
public class ReasonClassification {

    private static final Logger log = LoggerFactory.getLogger(ReasonClassification.class);

    /** Ordered: the first matching category wins. English and Spanish stems. */
    private static final List<Map.Entry<Category, List<String>>> KEYWORDS = List.of(
            Map.entry(Category.JOB_LOSS, List.of("lost my job", "laid off", "fired", "unemployed", "perdí mi trabajo",
                    "despedido", "despedida", "desempleado", "desempleada")),
            Map.entry(Category.MEDICAL, List.of("hospital", "medical", "sick", "doctor", "surgery", "enfermo", "enferma",
                    "médico", "medico", "cirugía")),
            Map.entry(Category.FAMILY_EMERGENCY, List.of("family", "funeral", "baby", "familia", "bebé", "bebe")),
            Map.entry(Category.UNEXPECTED_EXPENSE, List.of("car", "repair", "rent", "bill", "broke down", "carro", "coche",
                    "reparación", "renta", "factura")),
            Map.entry(Category.TIMING_ONLY, List.of("payday", "paid late", "next week", "día de pago", "la próxima semana")),
            Map.entry(Category.INCOME_REDUCED, List.of("hours", "slow", "sales", "fewer", "less work", "horas", "ventas",
                    "menos trabajo")));

    private final ObjectProvider<ReasonClassifier> classifiers;

    public ReasonClassification(ObjectProvider<ReasonClassifier> classifiers) {
        this.classifiers = classifiers;
    }

    public Category classify(String text, String locale) {
        ReasonClassifier model = classifiers.getIfAvailable();
        if (model != null) {
            try {
                ReasonClassifier.Classification c = model.classify(text, locale);
                if (c != null && c.category() != null) {
                    return c.category();
                }
            } catch (RuntimeException e) {
                log.info("Reason classifier unavailable ({}); using keywords", e.getClass().getSimpleName());
            }
        }
        return keywords(text);
    }

    static Category keywords(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<Category, List<String>> e : KEYWORDS) {
            for (String k : e.getValue()) {
                if (t.contains(k)) {
                    return e.getKey();
                }
            }
        }
        return Category.OTHER;
    }
}
