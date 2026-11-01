package com.paypalseed.governance.api.events;

import java.time.Instant;
import java.util.UUID;

/** Generic approval events. Owner modules translate them into their own domain events. */
public final class GovernanceEvents {

    private GovernanceEvents() {
    }

    public record ApprovalRequested(UUID eventId, UUID requestId, UUID subjectId, String tier, Instant slaDueAt) {
    }

    /**
     * {@code role} is MAKER, CHECKER or POLICY. {@code staffRef} is a pseudonymous reference, never the staff
     * id, so consumers and logs carry no direct identifier.
     */
    public record ApprovalDecided(UUID eventId, UUID requestId, UUID subjectId, String role, String decision,
                                  String requestStatus, String reasonCode, String staffRef) {
    }
}
