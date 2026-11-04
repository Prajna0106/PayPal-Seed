package com.paypalseed.engagement.domain.model;

import com.paypalseed.platform.kernel.Money;

/**
 * Texts about a loan that was closed because it never stood (ADR 0024). Calm and short, at the reading level of the other
 * member messages: the member owes nothing, nothing costs anything, and what happens next. The amount comes from the
 * event's structured field; PayPal's reason code is never put into a message.
 */
public final class LoanMessages {

    private LoanMessages() {
    }

    /**
     * The payout came back, so the loan was closed. {@code refundDueCents} is what the member had already paid in, which is
     * on its way back to them; zero when they had paid nothing.
     */
    public static String payoutReturned(String locale, long refundDueCents) {
        boolean es = "es".equals(locale);
        if (refundDueCents > 0) {
            String amount = amount(refundDueCents);
            return es
                    ? "Seed: El dinero de tu préstamo no llegó a tu cuenta de PayPal, así que cerramos el préstamo. No debes nada y "
                            + "no hay cargos. Los " + amount + " que pagaste te los devolvemos. Lo intentaremos de nuevo cuando tu "
                            + "cuenta de PayPal esté arreglada."
                    : "Seed: The money for your loan didn't reach your PayPal account, so we closed the loan. You owe nothing and "
                            + "there are no fees. The " + amount + " you paid is coming back to you. We'll try again once your "
                            + "PayPal account is fixed.";
        }
        return es
                ? "Seed: El dinero de tu préstamo no llegó a tu cuenta de PayPal, así que cerramos el préstamo. No debes nada y "
                        + "no hay cargos. Lo intentaremos de nuevo cuando tu cuenta de PayPal esté arreglada."
                : "Seed: The money for your loan didn't reach your PayPal account, so we closed the loan. You owe nothing and "
                        + "there are no fees. We'll try again once your PayPal account is fixed.";
    }

    /** The member cancelled inside the cooling-off window: a short confirmation, since they chose it. */
    public static String cancelled(String locale) {
        return cancelled(locale, 0);
    }

    /**
     * As {@link #cancelled(String)}; {@code refundDueCents} is what the member had already paid in, which is on its way back
     * to them. Zero when they had paid nothing, so no refund is promised.
     */
    public static String cancelled(String locale, long refundDueCents) {
        boolean es = "es".equals(locale);
        if (refundDueCents > 0) {
            String amount = amount(refundDueCents);
            return es
                    ? "Seed: Tu préstamo está cancelado. No debes nada y no hay cargos. Los " + amount + " que pagaste te los devolvemos."
                    : "Seed: Your loan is cancelled. You owe nothing and there are no fees. The " + amount
                            + " you paid is coming back to you.";
        }
        return es
                ? "Seed: Tu préstamo está cancelado. No debes nada y no hay cargos."
                : "Seed: Your loan is cancelled. You owe nothing and there are no fees.";
    }

    private static String amount(long cents) {
        return "$" + Money.ofCents(cents).toDecimalString().replace(".00", "");
    }
}
