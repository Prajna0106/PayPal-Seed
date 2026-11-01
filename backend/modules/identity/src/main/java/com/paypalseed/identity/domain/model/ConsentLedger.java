package com.paypalseed.identity.domain.model;

import com.paypalseed.identity.api.dto.ConsentType;
import java.util.List;
import java.util.Optional;

/**
 * The rules of one member's consent ledger, held in memory so they can be tested without a database. The database
 * enforces the same rules with a trigger and a unique index; the use case asks this class first.
 */
public final class ConsentLedger {

    /** What a grant has to do to the ledger. A line is never edited, so the steps only close lines and append new ones. */
    public enum Step {
        /** The same version is already active: change nothing. */
        ALREADY_ACTIVE,
        /** Append a new line. */
        APPEND,
        /** Close the active line of an older version, then append a new line. */
        CLOSE_AND_APPEND
    }

    private final List<ConsentEntry> lines;

    public ConsentLedger(List<ConsentEntry> lines) {
        this.lines = List.copyOf(lines);
    }

    public Optional<ConsentEntry> active(ConsentType type) {
        return lines.stream().filter(l -> l.type() == type && l.active()).findFirst();
    }

    public Step stepForGrant(ConsentType type, String version) {
        return active(type)
                .map(a -> a.version().equals(version) ? Step.ALREADY_ACTIVE : Step.CLOSE_AND_APPEND)
                .orElse(Step.APPEND);
    }
}
