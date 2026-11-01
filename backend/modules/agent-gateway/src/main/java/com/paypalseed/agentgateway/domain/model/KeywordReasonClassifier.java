package com.paypalseed.agentgateway.domain.model;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic English and Spanish keyword labelling, used when no model is available. It can only ever
 * return a category from the closed set, whatever the text says, so it is injection-proof by construction.
 */
public final class KeywordReasonClassifier {

    public record Label(String category, int confidencePct) {
    }

    /** First match wins, so the more specific categories come first. */
    private static final Map<String, List<String>> RULES = new LinkedHashMap<>();

    static {
        RULES.put("JOB_LOSS", List.of("laid off", "lost my job", "lost the job", "fired", "unemployed", "let go",
                "despedido", "despedida", "perdi mi trabajo", "perdi el trabajo", "sin trabajo", "desempleado", "desempleada"));
        RULES.put("MEDICAL", List.of("hospital", "sick", "illness", "doctor", "surgery", "medical", "injury", "injured",
                "enfermo", "enferma", "enfermedad", "medico", "cirugia", "lesion"));
        RULES.put("FAMILY_EMERGENCY", List.of("funeral", "passed away", "death in", "family emergency", "my child",
                "my mother", "my father", "my mom", "my dad", "emergencia familiar", "fallecio", "mi hijo", "mi hija",
                "mi madre", "mi padre", "mi mama", "mi papa"));
        RULES.put("UNEXPECTED_EXPENSE", List.of("car broke", "repair", "rent went up", "unexpected", "bill", "expense",
                "reparacion", "factura", "gasto", "renta subio", "imprevisto"));
        RULES.put("INCOME_REDUCED", List.of("hours got cut", "hours cut", "fewer hours", "less work", "sales are down",
                "sales down", "slow month", "business is slow", "fewer customers", "less income", "menos horas",
                "recortaron", "ventas bajas", "bajaron las ventas", "menos clientes", "menos trabajo", "menos ingresos"));
        RULES.put("TIMING_ONLY", List.of("payday", "paycheck", "get paid on", "paid late", "next week", "next friday",
                "dia de pago", "me pagan", "la proxima semana", "el proximo viernes", "cheque"));
    }

    private KeywordReasonClassifier() {
    }

    public static Label classify(String text) {
        if (text == null || text.isBlank()) {
            return new Label("OTHER", 0);
        }
        String folded = fold(text);
        for (Map.Entry<String, List<String>> rule : RULES.entrySet()) {
            for (String keyword : rule.getValue()) {
                if (folded.contains(keyword)) {
                    return new Label(rule.getKey(), 60);
                }
            }
        }
        return new Label("OTHER", 0);
    }

    /** Lower case without accents, so "perdí" matches "perdi". */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replaceAll("\\s+", " ");
    }
}
