package com.paypalseed.agentgateway.api;

import java.util.regex.Pattern;

/**
 * Last line of defence before text leaves the process for a model provider: masks email addresses,
 * phone numbers, card-like digit runs and PayPal-style ids. Tools are designed never to return these;
 * this catches what slips through, for example inside member text.
 */
public final class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern E164 = Pattern.compile("(?<![\\w])\\+\\d{8,15}(?!\\d)");
    private static final Pattern US_PHONE =
            Pattern.compile("(?<!\\d)(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}(?!\\d)");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");
    /** PayPal payer and transaction ids: 13 to 20 upper-case letters and digits, with at least one of each. */
    private static final Pattern PAYPAL_ID = Pattern.compile("\\b(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{13,20}\\b");

    private PiiRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = EMAIL.matcher(text).replaceAll("[email]");
        out = E164.matcher(out).replaceAll("[phone]");
        out = US_PHONE.matcher(out).replaceAll("[phone]");
        out = LONG_DIGITS.matcher(out).replaceAll("[number]");
        return PAYPAL_ID.matcher(out).replaceAll("[id]");
    }
}
