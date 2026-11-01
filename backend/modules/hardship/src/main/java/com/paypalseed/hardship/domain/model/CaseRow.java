package com.paypalseed.hardship.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A hardship case as stored. {@code riskLevel} is the deterministic floor, raised only by an accepted AI escalation. */
public record CaseRow(UUID id, UUID loanId, UUID memberId, Origin origin, CaseStatus status, UUID runId, int baseVersion,
                      int targetSeq, LocalDate targetDueDate, long targetAmountCents, String riskLevel, int points,
                      String policyVersion, String scorecardVersion, String reasonCategory, String dedupeKey,
                      LocalDate openedOn, String narrativeSource, Instant agentDeadlineAt, Instant outreachSentAt,
                      Instant reminderSentAt, String chosenOption, UUID approvalRequestId, Integer appliedVersion,
                      LocalDate closedOn, String closedReason, LocalDate cooldownUntil) {

    public enum Origin {
        PROACTIVE_SCAN,
        MEMBER_HELP,
        STAFF
    }

    /** The case reference lending stores on flags and holds. */
    public String caseRef() {
        return "case:" + id;
    }
}
