package com.paypalseed.fraud.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagStatus;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagView;
import com.paypalseed.fraud.api.dto.FraudDtos.ReviewDecision;
import com.paypalseed.fraud.api.events.FraudEvents.FraudFlagReviewed;
import com.paypalseed.fraud.domain.model.FraudErrors;
import com.paypalseed.fraud.domain.rules.RuleSet;
import com.paypalseed.fraud.infrastructure.persistence.FlagRepository;
import com.paypalseed.fraud.infrastructure.persistence.FlagRepository.FlagRow;
import com.paypalseed.fraud.infrastructure.persistence.ReviewRepository;
import com.paypalseed.fraud.infrastructure.persistence.ReviewRepository.ReviewRow;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An officer's review of a flag. One review per flag, written once and audited through governance; the member is never
 * blocked by it either way. The caller (the controller) has already checked the officer's role.
 */
@Service
public class FlagReview {

    private static final int MAX_NOTE = 1_000;
    private static final int MAX_KEY = 128;

    private final FlagRepository flags;
    private final ReviewRepository reviews;
    private final FlagQueries queries;
    private final GovernanceFacade governance;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    public FlagReview(FlagRepository flags, ReviewRepository reviews, FlagQueries queries, GovernanceFacade governance,
                      BusinessClock clock, ApplicationEventPublisher events, ObjectMapper json) {
        this.flags = flags;
        this.reviews = reviews;
        this.queries = queries;
        this.governance = governance;
        this.clock = clock;
        this.events = events;
        this.json = json;
    }

    @Transactional
    public FlagView review(UUID flagId, ReviewDecision decision, String note, UUID staffId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw new IllegalArgumentException("note is limited to " + MAX_NOTE + " characters");
        }
        if (decision == ReviewDecision.CONFIRM && cleanNote == null) {
            throw new DomainException(FraudErrors.NOTE_REQUIRED, "Say why the flag is confirmed");
        }
        String hash = hash(flagId, decision, cleanNote);
        FlagRow flag = flags.lock(flagId).orElseThrow(() -> new DomainException(FraudErrors.FLAG_NOT_FOUND, "No flag " + flagId));

        Optional<ReviewRow> existing = reviews.ofFlag(flagId);
        if (existing.isPresent()) {
            if (!existing.get().idempotencyKey().equals(idempotencyKey)) {
                throw new DomainException(FraudErrors.FLAG_ALREADY_REVIEWED, "Flag " + flagId + " was already reviewed");
            }
            if (!existing.get().requestHash().equals(hash)) {
                throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used for another review");
            }
            return queries.view(flag);
        }
        if (reviews.byKey(idempotencyKey).isPresent()) {
            throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used for another flag");
        }

        FlagStatus outcome = decision == ReviewDecision.CONFIRM ? FlagStatus.CONFIRMED : FlagStatus.REVIEWED_CLEAR;
        Instant now = clock.now();
        reviews.insert(Ids.newId(), flagId, decision, cleanNote, staffId, idempotencyKey, hash);
        flags.markReviewed(flagId, outcome, now);
        // The note stays in the review row: the audit log is hash-chained for good and carries the decision only.
        governance.audit(new AuditCommand("FRAUD_FLAG_REVIEWED", flagId, "STAFF", staffId.toString(), audit(flag, decision, outcome)));
        events.publishEvent(new FraudFlagReviewed(Ids.newId(), flagId, flag.memberId(), outcome, now));
        return queries.view(flags.find(flagId).orElseThrow());
    }

    private String audit(FlagRow flag, ReviewDecision decision, FlagStatus outcome) {
        try {
            return json.writeValueAsString(Map.of("flagId", flag.id(), "memberId", flag.memberId(), "decision", decision.name(),
                    "outcome", outcome.name(), "score", flag.score(), "rules", RuleSet.VERSION));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise an audit payload", e);
        }
    }

    private static String hash(UUID flagId, ReviewDecision decision, String note) {
        try {
            String text = flagId + "|" + decision + "|" + (note == null ? "" : note);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
