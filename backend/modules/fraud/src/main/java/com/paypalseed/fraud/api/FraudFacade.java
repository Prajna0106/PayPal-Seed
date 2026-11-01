package com.paypalseed.fraud.api;

import com.paypalseed.fraud.api.dto.FraudDtos.FlagDetail;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagEvidence;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagPage;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagStatus;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagView;
import com.paypalseed.fraud.api.dto.FraudDtos.Observation;
import com.paypalseed.fraud.api.dto.FraudDtos.ReviewDecision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fraud signals and flags (ADR 0019). Advisory only: a flag is input for officers and for the fair-check queue and never
 * blocks a member by itself. Every signal is a named rule with a weight and the facts behind it.
 */
public interface FraudFacade {

    /**
     * Records one fact and re-evaluates the member's rules. Idempotent on the observation's {@code sourceKey}. Runs in
     * the caller's transaction when there is one. The adapters in {@code app} feed it what no fraud listener can import:
     * account creation and loan applications (ADR 0021).
     */
    void observe(Observation observation);

    /**
     * The member's payout destination as it stands now ({@code reference} is hashed in the fraud module and never stored
     * or returned). The first destination seen is a baseline and records nothing; a different one than last time records
     * a {@code PAYOUT_RECIPIENT_CHANGED} fact, idempotent on {@code sourceKey}; the same one again records nothing, so a
     * profile update that left the destination alone is never a signal. Runs in the caller's transaction when there is one.
     */
    void payoutDestination(UUID memberId, String reference, Instant occurredAt, String sourceKey);

    /** Open flags, newest first, at most 200. */
    List<FlagView> openFlags();

    FlagPage flags(List<FlagStatus> statuses, UUID before, int limit);

    /** The member's open flag, or else the latest reviewed one. */
    Optional<FlagView> flagOf(UUID memberId);

    Optional<FlagDetail> flag(UUID flagId);

    /** Signals with the facts they were computed from, for a dispute. */
    Optional<FlagEvidence> evidence(UUID flagId);

    /**
     * An officer's review. The caller checks the role. Writes an audit entry through governance. Idempotent on
     * {@code idempotencyKey}; a flag that was already reviewed under another key is {@code fraud-flag-already-reviewed}.
     * A confirmation needs a note.
     */
    FlagView review(UUID flagId, ReviewDecision decision, String note, UUID staffId, String idempotencyKey);
}
