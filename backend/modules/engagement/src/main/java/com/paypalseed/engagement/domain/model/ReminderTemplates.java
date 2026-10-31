package com.paypalseed.engagement.domain.model;

import com.paypalseed.platform.kernel.Money;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Member messages in plain English and Spanish, about a grade-6 reading level (NFR-11). Kind
 * wording, the amount and the date, and what the member can do next. No fees are ever mentioned
 * because there are none.
 */
public final class ReminderTemplates {

    public enum Type {
        DUE_SOON,
        PAYMENT_FAILED,
        GRACE_NUDGE,
        AUTOPAY_STOPPED,
        AUTOPAY_ATTENTION
    }

    private ReminderTemplates() {
    }

    public static String render(Type type, String locale, long amountCents, LocalDate date) {
        boolean es = "es".equals(locale);
        String amount = "$" + Money.ofCents(amountCents).toDecimalString().replace(".00", "");
        String day = date == null ? "" : date.format(DateTimeFormatter.ofPattern(es ? "EEEE d 'de' MMMM" : "EEEE, MMMM d",
                es ? Locale.forLanguageTag("es") : Locale.US));
        return switch (type) {
            case DUE_SOON -> es
                    ? "Seed: Tu pago de " + amount + " vence el " + day + ". Si este mes está difícil, responde AYUDA."
                    : "Seed: Your " + amount + " payment is due " + day + ". If this month is tight, reply HELP.";
            case PAYMENT_FAILED -> es
                    ? "Seed: Tu pago de " + amount + " no se pudo cobrar. Sin cargos. Responde PAGAR para un enlace o AYUDA."
                    : "Seed: Your " + amount + " payment didn't go through. No fees. Reply PAY for a link or HELP for options.";
            case GRACE_NUDGE -> es
                    ? "Seed: Aún puedes pagar " + amount + " sin afectar tu historial. Responde PAGAR o AYUDA."
                    : "Seed: You can still pay " + amount + " and stay on track. Reply PAY for a link or HELP for options.";
            case AUTOPAY_STOPPED -> es
                    ? "Seed: Tu pago automático se detuvo. Te enviaremos un recordatorio antes de cada pago."
                    : "Seed: Your autopay stopped. We'll remind you before each payment so nothing is missed.";
            case AUTOPAY_ATTENTION -> es
                    ? "Seed: Tu pago automático necesita tu aprobación en PayPal. Mientras tanto te enviaremos un enlace para pagar."
                    : "Seed: Your autopay needs your OK in PayPal. Until then we'll send you a link to pay.";
        };
    }
}
