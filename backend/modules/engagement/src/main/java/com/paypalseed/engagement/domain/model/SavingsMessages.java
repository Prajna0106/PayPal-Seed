package com.paypalseed.engagement.domain.model;

import com.paypalseed.platform.kernel.Money;

/**
 * Texts about a withdrawal that did not reach the member. Calm and short, at the reading level of the other member
 * messages: the money is safe, nothing costs anything, and what to do next. The amount comes from the event's structured
 * field; PayPal's reason code is never put into a message.
 */
public final class SavingsMessages {

    private SavingsMessages() {
    }

    /** PayPal could not deliver the payout, and the money went back into the goal. */
    public static String withdrawalFailed(String locale, long amountCents) {
        String amount = amount(amountCents);
        return "es".equals(locale)
                ? "Seed: Tu retiro de " + amount + " no llegó a PayPal y volvió a tu meta. Tu dinero está seguro y no hay cargos. "
                        + "Puedes intentarlo otra vez en la app."
                : "Seed: Your " + amount + " withdrawal didn't reach PayPal, so it's back in your goal. Your money is safe and there "
                        + "are no fees. You can try again in the app.";
    }

    /** PayPal took the payout back (unclaimed, or reversed), and the money is in the goal again. */
    public static String withdrawalReturned(String locale, long amountCents) {
        String amount = amount(amountCents);
        return "es".equals(locale)
                ? "Seed: PayPal devolvió tu retiro de " + amount + " y el dinero volvió a tu meta. Tu dinero está seguro y no hay "
                        + "cargos. Puedes intentarlo otra vez en la app."
                : "Seed: PayPal sent your " + amount + " withdrawal back, so the money is in your goal again. Your money is safe and "
                        + "there are no fees. You can try again in the app.";
    }

    private static String amount(long cents) {
        return "$" + Money.ofCents(cents).toDecimalString().replace(".00", "");
    }
}
