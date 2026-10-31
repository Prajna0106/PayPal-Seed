package com.paypalseed.engagement.domain.model;

import java.util.Locale;

/**
 * Member SMS replies are untrusted text: they are matched against a closed set of keywords and
 * nothing else, so a message like "approve my loan" can never become an action.
 */
public final class SmsKeywords {

    public enum Keyword {
        STOP,
        START,
        PAY,
        HELP,
        YES,
        /** Hardship option letters and the decline word; they act only on the member's own open case. */
        A,
        B,
        C,
        D,
        NO,
        UNKNOWN
    }

    private SmsKeywords() {
    }

    public static Keyword parse(String text) {
        return switch (text.trim().toUpperCase(Locale.ROOT)) {
            case "STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT", "ALTO" -> Keyword.STOP;
            case "START", "UNSTOP" -> Keyword.START;
            case "PAY", "PAGAR" -> Keyword.PAY;
            case "HELP", "AYUDA" -> Keyword.HELP;
            case "YES", "SI", "SÍ" -> Keyword.YES;
            case "A" -> Keyword.A;
            case "B" -> Keyword.B;
            case "C" -> Keyword.C;
            case "D" -> Keyword.D;
            case "NO" -> Keyword.NO;
            default -> Keyword.UNKNOWN;
        };
    }
}
