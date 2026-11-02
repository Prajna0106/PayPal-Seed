package com.paypalseed.deskquery.domain.model;

import java.util.Set;

/**
 * What the fair check amounts to for the desk. A pending assessment shows the engine's recommendation; once an officer
 * decides, the officer's decision is the outcome.
 */
public final class AssessmentOutcome {

    public static final String RECOMMENDED = "RECOMMENDED";
    public static final String NOT_RECOMMENDED = "NOT_RECOMMENDED";
    public static final String PENDING = "PENDING";
    public static final Set<String> OFFICER_DECISIONS = Set.of("APPROVED", "ADJUSTED", "DECLINED");
    public static final Set<String> STATUSES = Set.of(PENDING, "APPROVED", "ADJUSTED", "DECLINED");
    public static final Set<String> OUTCOMES = Set.of(RECOMMENDED, NOT_RECOMMENDED, "APPROVED", "ADJUSTED", "DECLINED");

    private AssessmentOutcome() {
    }

    /** The queue status: PENDING until an officer decides. Anything unrecognised counts as still pending. */
    public static String status(String officerDecision) {
        return officerDecision != null && OFFICER_DECISIONS.contains(officerDecision) ? officerDecision : PENDING;
    }

    public static String of(boolean recommended, String officerDecision) {
        String status = status(officerDecision);
        if (!PENDING.equals(status)) {
            return status;
        }
        return recommended ? RECOMMENDED : NOT_RECOMMENDED;
    }
}
