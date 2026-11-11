package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.domain.model.DeskBriefingFigures;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The fixed wording of the morning briefing, built only from the figures it is given. It is what an officer reads when the
 * analyst agent is off or its text fails the grounding check, so every number in it is one of the inputs. Dollar amounts
 * use the same format the agent's grounding check accepts ({@code $1,234} or {@code $1,234.56}).
 */
final class BriefingTemplates {

    private final String summary;
    private final List<String> priorities;
    private final List<String> observations;
    private final String staffing;

    private BriefingTemplates(String summary, List<String> priorities, List<String> observations, String staffing) {
        this.summary = summary;
        this.priorities = priorities;
        this.observations = observations;
        this.staffing = staffing;
    }

    static BriefingTemplates of(DeskBriefingFigures f, long reserveCents, Double coverage) {
        String summary = f.activeLoans() == 0
                ? "The portfolio has no active loans today."
                : "Portfolio holds " + count(f.activeLoans(), "active loan") + " (" + dollars(f.outstandingCents())
                        + " outstanding) with " + count(f.healthyLoans(), "healthy loan") + " and " + f.atRiskLoans()
                        + " marked at risk.";

        List<String> priorities = new ArrayList<>();
        priorities.add(f.openHardshipCases() == 0
                ? "No hardship cases are open."
                : "Review " + count(f.openHardshipCases(), "open hardship case") + " before close of business.");
        if (f.slaBreaches() > 0) {
            priorities.add(count(f.slaBreaches(), "queue item") + " past its service deadline: clear these first.");
        } else if (f.openHardshipCases() > 0) {
            priorities.add("Oldest open case is " + count(f.oldestOpenCaseHours(), "hour") + " old; no SLA breaches today.");
        } else {
            priorities.add("No SLA breaches today.");
        }

        List<String> observations = new ArrayList<>();
        observations.add(f.activeLoans() == 0
                ? "There are no active loans to assess."
                : "Healthy loans are " + (f.healthyLoans() * 100L / f.activeLoans()) + "% of the active portfolio.");
        observations.add(coverage == null
                ? "Community reserve holds " + dollars(reserveCents) + "; no loan needs cover right now."
                : "Community reserve (" + dollars(reserveCents) + ") covers open exposure "
                        + String.format(Locale.US, "%.2f", coverage) + "x.");

        String staffing = f.openHardshipCases() > 0
                ? "Focus primary officer capacity on " + count(f.openHardshipCases(), "open hardship case") + "."
                : "No hardship cases are open; use officer time on the application queue.";
        return new BriefingTemplates(summary, List.copyOf(priorities), List.copyOf(observations), staffing);
    }

    String summary() {
        return summary;
    }

    List<String> priorities() {
        return priorities;
    }

    List<String> observations() {
        return observations;
    }

    String staffing() {
        return staffing;
    }

    private static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    static String dollars(long cents) {
        long whole = cents / 100;
        long rest = Math.abs(cents % 100);
        return rest == 0 ? String.format(Locale.US, "$%,d", whole) : String.format(Locale.US, "$%,d.%02d", whole, rest);
    }
}
