package com.paypalseed.identity.domain.model;

import com.paypalseed.identity.api.dto.ConsentType;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a member's consent ledger. A line is never edited: closing it sets the revocation time once, and
 * agreeing again appends a new line.
 */
public record ConsentEntry(UUID id, UUID memberId, ConsentType type, String version, Instant grantedAt, Instant revokedAt) {

    public boolean active() {
        return revokedAt == null;
    }

    /**
     * The same line with its revocation time set. A closed line stays closed. A clock that reads earlier than the grant
     * (a demo clock moved back) never blocks a revocation: the line ends when it began.
     */
    public ConsentEntry closedAt(Instant when) {
        if (revokedAt != null) {
            throw new IllegalStateException("Consent " + id + " is already closed");
        }
        return new ConsentEntry(id, memberId, type, version, grantedAt, when.isBefore(grantedAt) ? grantedAt : when);
    }
}
