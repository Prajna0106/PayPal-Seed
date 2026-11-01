package com.paypalseed.app.home;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.NextStepView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One short, pressure-free sentence about what is missing for the next record level, built from the counts the credit
 * record computed. The words are fixed templates; no number in them is worked out here and no model writes them.
 */
final class NextStepHint {

    private NextStepHint() {
    }

    static String of(NextStepView next, String language) {
        boolean es = "es".equals(language);
        String level = display(next.level());
        List<String> parts = new ArrayList<>();
        if (next.onTimeSavingMonthsToGo() > 0) {
            int n = next.onTimeSavingMonthsToGo();
            parts.add(es ? n + (n == 1 ? " mes más" : " meses más") + " de ahorro puntual"
                    : n + " more on-time saving " + (n == 1 ? "month" : "months"));
        }
        if (next.loansRepaidOnPlanToGo() > 0) {
            int n = next.loansRepaidOnPlanToGo();
            parts.add(es ? n + (n == 1 ? " préstamo más" : " préstamos más") + " pagado según el plan"
                    : n + " more " + (n == 1 ? "loan" : "loans") + " repaid on plan");
        }
        if (next.latePaymentOpen()) {
            return es ? "Ponte al día con tu pago pendiente y sigues avanzando hacia " + level + "."
                    : "Catch up on your open payment and you are back on the way to " + level + ".";
        }
        if (parts.isEmpty()) {
            return es ? "Sigue pagando a tiempo para llegar a " + level + "."
                    : "Keep paying on time to reach " + level + ".";
        }
        String joined = String.join(es ? " y " : " and ", parts);
        return es ? "Para llegar a " + level + ": " + joined + "." : "To reach " + level + ": " + joined + ".";
    }

    private static String display(String level) {
        String lower = level.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
