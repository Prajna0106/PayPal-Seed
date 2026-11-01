package com.paypalseed.deskquery.domain.model;

import com.paypalseed.deskquery.domain.model.FilterDefinition.Flag;
import com.paypalseed.deskquery.domain.model.FilterDefinition.OneOf;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The three desk lists: what a request may sort and filter by. The persistence layer binds each name to a column. */
public final class Grids {

    public static final Set<String> APPLICATION_STATUSES = Set.of("SUBMITTED", "PENDING_SECOND_APPROVAL", "APPROVED", "DISBURSING",
            "DISBURSED", "REJECTED", "CANCELLED_COOLING_OFF");
    /** The statuses that wait for an officer: these are the ones with a running SLA. */
    public static final List<String> OPEN_APPLICATION_STATUSES = List.of("SUBMITTED", "PENDING_SECOND_APPROVAL");
    public static final Set<String> LOAN_STATUSES = Set.of("ACTIVE", "REPAID", "COVERED", "CANCELLED");

    public static final GridDefinition APPLICATIONS = new GridDefinition("applications",
            Map.of("submittedAt", ColumnType.INSTANT,
                    "slaDueAt", ColumnType.INSTANT,
                    "requestedAmountCents", ColumnType.LONG,
                    "termMonths", ColumnType.INT,
                    "savingStreakMonths", ColumnType.INT,
                    "status", ColumnType.STRING,
                    "assessmentOutcome", ColumnType.STRING,
                    "assignedOfficer", ColumnType.STRING),
            List.of(new OneOf("status", APPLICATION_STATUSES),
                    new Flag("slaBreached"),
                    new OneOf("assessmentOutcome", AssessmentOutcome.OUTCOMES)),
            List.of(new SortKey("slaDueAt", false)));

    public static final GridDefinition ASSESSMENTS = new GridDefinition("assessments",
            Map.of("createdAt", ColumnType.INSTANT,
                    "recommendedAmountCents", ColumnType.LONG,
                    "status", ColumnType.STRING),
            List.of(new OneOf("status", AssessmentOutcome.STATUSES),
                    new Flag("disputed"),
                    new Flag("recommended")),
            List.of(new SortKey("createdAt", true)));

    public static final GridDefinition LOANS = new GridDefinition("loans",
            Map.of("disbursedOn", ColumnType.DATE,
                    "principalCents", ColumnType.LONG,
                    "outstandingCents", ColumnType.LONG,
                    "nextDueDate", ColumnType.DATE,
                    "daysLate", ColumnType.INT,
                    "agingBucket", ColumnType.INT,
                    "status", ColumnType.STRING),
            List.of(new OneOf("status", LOAN_STATUSES),
                    new OneOf("agingBucket", Set.copyOf(AgingBuckets.ORDER)),
                    new Flag("autopay"),
                    new Flag("hardship")),
            List.of(new SortKey("disbursedOn", true)));

    private Grids() {
    }
}
