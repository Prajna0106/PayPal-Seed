package com.paypalseed.creditrecord.application.usecases;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.NextStepView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.engines.rules.record.RecordLevel;
import java.util.Locale;
import java.util.Optional;

/**
 * The fixed wording of the trust record, built only from the member's own record and figures. It is what the member and a
 * third party read when the trust agent is off or its text fails the grounding check, so every number in it is one the
 * backend computed. The levels are the record's (ADR 0014); there is no score and no second ladder.
 */
final class TrustTemplates {

    private final String summaryEn;
    private final String summaryEs;
    private final String attestation;
    private final String advice;

    private TrustTemplates(String summaryEn, String summaryEs, String attestation, String advice) {
        this.summaryEn = summaryEn;
        this.summaryEs = summaryEs;
        this.attestation = attestation;
        this.advice = advice;
    }

    static TrustTemplates of(RecordView view, int streakMonths, long savedCents, long repaidCents) {
        RecordLevel level = RecordLevel.valueOf(view.level());
        Optional<RecordLevel> next = level.next();
        int onTime = view.instalments().onTime();

        String figuresEn = countEn(onTime, "on-time repayment") + ", " + countEn(streakMonths, "month") + " of saving in a row and "
                + dollars(savedCents) + " saved";
        String figuresEs = countEs(onTime, "pago puntual", "pagos puntuales") + ", " + countEs(streakMonths, "mes", "meses")
                + " seguidos ahorrando y " + dollars(savedCents) + " ahorrados";

        String summaryEn = level == RecordLevel.NEW
                ? "Your Seed record is just beginning: " + figuresEn + ". Saving and repaying on time builds it."
                : "You are at the " + nameEn(level) + " level of your Seed record, with " + figuresEn + "."
                        + next.map(n -> " The next level, " + nameEn(n) + ", unlocks a loan limit of " + dollars(n.loanLimitCents())
                                + ".").orElse(" This is the highest level.");
        String summaryEs = level == RecordLevel.NEW
                ? "Tu historial en Seed apenas comienza: " + figuresEs + ". Ahorrar y pagar a tiempo lo hace crecer."
                : "Estás en el nivel " + nameEs(level) + " de tu historial en Seed, con " + figuresEs + "."
                        + next.map(n -> " El siguiente nivel, " + nameEs(n) + ", habilita un límite de préstamo de "
                                + dollars(n.loanLimitCents()) + ".").orElse(" Este es el nivel más alto.");

        String attestation = "Community Credit Standing: level " + nameEn(level) + ". " + countEn(onTime, "on-time repayment")
                + " recorded, " + dollars(repaidCents) + " repaid in total, " + countEn(view.defaults(), "default event")
                + " on record.";

        return new TrustTemplates(summaryEn, summaryEs, attestation, adviceFor(next, view.next()));
    }

    private static String adviceFor(Optional<RecordLevel> next, NextStepView step) {
        if (next.isEmpty() || step == null) {
            return "Keep saving and repaying on time to stay at your level.";
        }
        String target = nameEn(next.get());
        if (step.latePaymentOpen()) {
            return "Paying the instalment that is past due keeps you on the way to " + target + ".";
        }
        StringBuilder parts = new StringBuilder();
        if (step.onTimeSavingMonthsToGo() > 0) {
            parts.append(countEn(step.onTimeSavingMonthsToGo(), "more month")).append(" of saving");
        }
        if (step.loansRepaidOnPlanToGo() > 0) {
            parts.append(parts.length() > 0 ? " and " : "")
                    .append(countEn(step.loansRepaidOnPlanToGo(), "more loan")).append(" repaid on plan");
        }
        if (parts.length() > 0) {
            return parts + " lead to " + target + " (" + dollars(next.get().loanLimitCents()) + " limit).";
        }
        if (step.onTimeShareTooLow()) {
            return "Paying on time from here lifts your on-time share toward " + target + ".";
        }
        return "You meet the steps for " + target + "; your level updates at the next record update.";
    }

    String summaryEn() {
        return summaryEn;
    }

    String summaryEs() {
        return summaryEs;
    }

    String attestation() {
        return attestation;
    }

    String advice() {
        return advice;
    }

    static String nameEn(RecordLevel level) {
        return switch (level) {
            case NEW -> "New";
            case BUILDING -> "Building";
            case STEADY -> "Steady";
            case TRUSTED -> "Trusted";
            case EXEMPLARY -> "Exemplary";
        };
    }

    private static String nameEs(RecordLevel level) {
        return switch (level) {
            case NEW -> "Nuevo";
            case BUILDING -> "En construcción";
            case STEADY -> "Constante";
            case TRUSTED -> "De confianza";
            case EXEMPLARY -> "Ejemplar";
        };
    }

    private static String countEn(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String countEs(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** The same format the trust agent's grounding check accepts: {@code $1,234} or {@code $1,234.56}. */
    static String dollars(long cents) {
        long whole = cents / 100;
        long rest = Math.abs(cents % 100);
        return rest == 0 ? String.format(Locale.US, "$%,d", whole) : String.format(Locale.US, "$%,d.%02d", whole, rest);
    }
}
