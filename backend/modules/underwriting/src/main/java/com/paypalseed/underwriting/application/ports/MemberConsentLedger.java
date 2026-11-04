package com.paypalseed.underwriting.application.ports;

import java.util.UUID;

/**
 * The member-facing consent list (identity, ADR 0012). Underwriting enforces its own consent; this keeps the list the
 * member sees in step with it (ADR 0026). Both calls are idempotent and do nothing for a member with no identity
 * record, because there is no list to keep.
 */
public interface MemberConsentLedger {

    /** The two lines of the member's list that stand for the one consent underwriting enforces. */
    enum Line {
        FAIR_CHECK,
        INCOME_READ
    }

    /** The member gave the fair-check consent: record it in the list, once per consent text version. */
    void fairCheckGiven(UUID memberId);

    /**
     * The consent underwriting enforces is closed. It is one consent behind two lines of the list (fair check and
     * income read), so both lines are closed.
     */
    void closed(UUID memberId);

    /**
     * The member took {@code line} off the list, and underwriting is now following. Closes the other line. Returns
     * false, and closes nothing, when {@code line} is active again: events are delivered later, and an old
     * withdrawal must not undo a newer agreement.
     */
    boolean withdrawn(UUID memberId, Line line);
}
