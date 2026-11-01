package com.paypalseed.fraud.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class FraudDtos {

    private FraudDtos() {
    }

    /** A flag is advice for people. It never blocks, limits or prices anything on its own. */
    public enum FlagStatus {
        OPEN,
        REVIEWED_CLEAR,
        CONFIRMED
    }

    public enum ReviewDecision {
        CLEAR,
        CONFIRM
    }

    /** The kinds of fact the rules read. Each is a thing that happened, never a trait of the person. */
    public enum FactKind {
        ACCOUNT_CREATED,
        GOAL_CREATED,
        DEPOSIT,
        WITHDRAWAL,
        APPLICATION_SUBMITTED,
        LOAN_DISBURSED,
        PAYMENT_FAILED,
        /** A PayPal payer id was seen paying for this member. */
        PAYER_SEEN,
        PAYOUT_RECIPIENT_CHANGED
    }

    /**
     * One thing that happened. {@code reference} is a stable identifier such as a PayPal payer id or a payout recipient: it
     * is hashed with a secret salt inside the fraud module and never stored or returned in clear. {@code attributes} keeps
     * only the allowed keys ({@code goalId}, {@code loanId}); anything else is dropped. {@code sourceKey} makes the
     * observation idempotent (use the event id).
     */
    public record Observation(UUID memberId, FactKind kind, Instant occurredAt, String sourceKey, Long amountCents,
                              String reference, Map<String, String> attributes) {
        public Observation {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(sourceKey, "sourceKey");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record FlagView(UUID flagId, UUID memberId, FlagStatus status, int score, List<String> signalCodes,
                           Instant raisedAt, Instant reviewedAt) {
        public FlagView {
            signalCodes = List.copyOf(signalCodes);
        }
    }

    public record FlagPage(List<FlagView> rows, UUID nextBefore) {
        public FlagPage {
            rows = List.copyOf(rows);
        }
    }

    /** One rule that fired: a stable code, how much it counts, a plain sentence and the numbers behind it. */
    public record SignalView(String code, int weight, String explanation, Instant detectedAt, Map<String, Object> evidence) {
        public SignalView {
            evidence = Map.copyOf(evidence);
        }
    }

    public record ReviewView(ReviewDecision decision, String note, Instant reviewedAt) {
    }

    public record FlagDetail(FlagView flag, List<SignalView> signals, ReviewView review, String policyVersion,
                             int flagThreshold) {
        public FlagDetail {
            signals = List.copyOf(signals);
        }
    }

    /** A fact behind a signal. {@code referenceTag} is a short tag of the hashed reference, enough to match two facts. */
    public record FactView(long factId, FactKind kind, Instant occurredAt, Long amountCents, String referenceTag,
                           Map<String, String> attributes) {
        public FactView {
            attributes = Map.copyOf(attributes);
        }
    }

    public record SignalEvidence(SignalView signal, List<FactView> facts) {
        public SignalEvidence {
            facts = List.copyOf(facts);
        }
    }

    /** The evidence pack for a dispute: every signal with the facts it was computed from. */
    public record FlagEvidence(UUID flagId, UUID memberId, FlagStatus status, int score, String policyVersion,
                               int flagThreshold, Instant generatedAt, List<SignalEvidence> signals, ReviewView review) {
        public FlagEvidence {
            signals = List.copyOf(signals);
        }
    }
}
