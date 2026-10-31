package com.paypalseed.hardship.domain.model;

import com.paypalseed.engines.rules.hardship.Signal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The deterministic texts used whenever the AI is off, fails, or is not trusted (02-design §4.8). Every number
 * comes from the case; nothing here is generated. Plain words, about a grade-6 reading level, no pressure, and
 * no promise of an outcome: an option is only ever offered, never granted, in these words.
 */
public final class NarrativeTemplates {

    public static final String CONSENT_TEXT_VERSION = "hardship-consent-v1";

    private static final DateTimeFormatter EN_DATE = DateTimeFormatter.ofPattern("MMMM d", Locale.US);
    private static final DateTimeFormatter ES_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es"));

    private NarrativeTemplates() {
    }

    /** The member paragraph that sits between engagement's fixed header and the option list. */
    public static String memberParagraph(String locale, long amountCents, LocalDate dueDate, boolean memberAsked) {
        String amount = dollars(amountCents);
        if ("es".equals(locale)) {
            String opening = memberAsked ? "Gracias por avisarnos." : "Parece que este mes el dinero está más justo.";
            return opening + " Tu pago de " + amount + " vence el " + dueDate.format(ES_DATE)
                    + ". Puedes elegir otra forma de pagarlo. Tú decides y puedes decir que no.";
        }
        String opening = memberAsked ? "Thanks for telling us." : "Money looks tighter than usual this month.";
        return opening + " Your " + amount + " payment is due " + dueDate.format(EN_DATE)
                + ". You can pick another way to pay it. You choose, and you can say no.";
    }

    /** The officer's case brief: the scorecard's own reasons, with every number from the evidence. */
    public static String officerBrief(String level, int points, List<Signal> signals, int targetSeq, long amountCents,
                                      LocalDate dueDate, String recommended, String reasonCategory) {
        String reasons = signals.stream()
                .map(s -> s.code() + " " + s.points() + describe(s.evidence()))
                .collect(Collectors.joining("; "));
        return "Level " + level + " at " + points + " points. Signals: " + (reasons.isEmpty() ? "none" : reasons) + ". "
                + "Instalment " + targetSeq + " of " + dollars(amountCents) + " is due " + dueDate + ". "
                + (recommended == null ? "No option is suggested (no income data)." : "Suggested option: " + recommended + ".")
                + (reasonCategory == null ? "" : " Member's stated reason: " + reasonCategory + ".");
    }

    private static String describe(Map<String, Long> evidence) {
        if (evidence.isEmpty()) {
            return "";
        }
        return evidence.entrySet().stream()
                .map(e -> e.getKey().endsWith("Cents") ? e.getKey() + " " + dollars(e.getValue())
                        : e.getKey().endsWith("Bps") ? e.getKey() + " " + percent(e.getValue())
                        : e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", ", " (", ")"));
    }

    public static String dollars(long cents) {
        long whole = cents / 100;
        long rest = Math.abs(cents % 100);
        String digits = String.format(Locale.US, "%,d", whole);
        return rest == 0 ? "$" + digits : "$" + digits + "." + (rest < 10 ? "0" + rest : String.valueOf(rest));
    }

    private static String percent(long bps) {
        return bps % 100 == 0 ? (bps / 100) + "%" : String.format(Locale.US, "%.2f%%", bps / 100.0);
    }
}
