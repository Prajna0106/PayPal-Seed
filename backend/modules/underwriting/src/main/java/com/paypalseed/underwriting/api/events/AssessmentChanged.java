package com.paypalseed.underwriting.api.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A whole-state snapshot of a fair-check assessment, published inside the transaction that created, disputed or
 * decided it. It is a snapshot, not a delta, so a repeated or late delivery cannot corrupt a read model that keeps
 * the newest {@code changedAt}. It carries no name, no email and no free text a member or officer typed.
 *
 * <p>{@code changedAt} is the decision time once decided and the creation time before that: a dispute alone keeps
 * the creation time. Narration changes only the reasons, which are not part of the snapshot, and happens in the same
 * transaction as the creation or the dispute, so it has no event of its own.
 */
public record AssessmentChanged(
        UUID eventId,
        UUID assessmentId,
        UUID memberId,
        UUID applicationId,
        boolean recommended,
        long recommendedAmountCents,
        String officerDecision,
        boolean memberDisputed,
        Instant createdAt,
        Instant changedAt) {
}
