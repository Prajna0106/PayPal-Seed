package com.paypalseed.engagement.domain.model;

import com.paypalseed.platform.kernel.Money;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hardship texts. Engagement owns the fixed frame: a header, the option lines built from the engine's numbers, and
 * a footer with how to choose, decline and opt out. Only the middle paragraph comes from hardship, and that is
 * either a template or an AI paragraph that passed the grounding check, so a model never writes the frame.
 */
public final class HardshipMessages {

    /** One offered option as engagement receives it: a label and the engine's summary numbers. */
    public record OfferedOption(String label, boolean recommended, Map<String, Long> summary) {
    }

    private static final DateTimeFormatter EN = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter ES = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es"));

    private HardshipMessages() {
    }

    public static String offer(String locale, String paragraph, List<OfferedOption> options, boolean reminder) {
        boolean es = "es".equals(locale);
        String header = reminder ? (es ? "Seed (recordatorio):" : "Seed (reminder):") : "Seed:";
        String lines = options.stream().map(o -> line(o, es)).collect(Collectors.joining(" "));
        String letters = options.stream().map(OfferedOption::label).collect(Collectors.joining(", "));
        String footer = es
                ? "Responde " + letters + " para elegir, o NO. Sin cargos. Responde STOP para no recibir mensajes."
                : "Reply " + letters + " to choose, or NO. No fees. Reply STOP to opt out.";
        return header + " " + (paragraph == null ? "" : paragraph + " ") + lines + " " + footer;
    }

    public static String applied(String locale) {
        return "es".equals(locale)
                ? "Seed: Tu nuevo plan está listo. Puedes ver tus próximos pagos en la app."
                : "Seed: Your new plan is ready. You can see your next payments in the app.";
    }

    public static String rejected(String locale) {
        return "es".equals(locale)
                ? "Seed: No pudimos hacer este cambio y tu plan sigue igual. Responde AYUDA para ver otras opciones."
                : "Seed: We could not make this change, so your plan stays the same. Reply HELP to see other options.";
    }

    private static String line(OfferedOption o, boolean es) {
        Map<String, Long> s = o.summary();
        String tag = o.recommended() ? (es ? " (sugerida)" : " (suggested)") : "";
        String text = switch (o.label()) {
            case "A" -> es ? "paga " + money(s, "amountCents") + " el " + date(s, "movedToDate", true)
                    : "pay " + money(s, "amountCents") + " on " + date(s, "movedToDate", false);
            case "B" -> es ? "pausa este pago; pasa al " + date(s, "addedOnDate", true)
                    : "pause this payment; it moves to " + date(s, "addedOnDate", false);
            case "C" -> es ? "pagos más pequeños, hasta " + money(s, "maxInstalmentCents") + ", el último el " + date(s, "lastDueDate", true)
                    : "smaller payments, up to " + money(s, "maxInstalmentCents") + ", the last on " + date(s, "lastDueDate", false);
            case "D" -> es ? "paga " + money(s, "payNowCents") + " ahora y " + money(s, "stillDueCents") + " el " + date(s, "stillDueOnDate", true)
                    : "pay " + money(s, "payNowCents") + " now and " + money(s, "stillDueCents") + " on " + date(s, "stillDueOnDate", false);
            default -> "";
        };
        return o.label() + ": " + text + tag + ".";
    }

    private static String money(Map<String, Long> s, String key) {
        Long cents = s.get(key);
        return cents == null ? "" : "$" + Money.ofCents(cents).toDecimalString().replace(".00", "");
    }

    private static String date(Map<String, Long> s, String key, boolean es) {
        Long day = s.get(key);
        return day == null ? "" : LocalDate.ofEpochDay(day).format(es ? ES : EN);
    }
}
