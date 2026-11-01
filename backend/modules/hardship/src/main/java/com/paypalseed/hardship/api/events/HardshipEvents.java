package com.paypalseed.hardship.api.events;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Facts hardship publishes. Consumers never need a hardship type beyond these records. */
public final class HardshipEvents {

    private HardshipEvents() {
    }

    public sealed interface HardshipEvent permits HardshipCaseOpened, HardshipSignalDetected, HardshipOfferIssued,
            HardshipOptionChosen, HardshipDecisionMade, HardshipApplied, HardshipCaseClosed, GuardianRunRequested {
        UUID eventId();

        UUID caseId();

        UUID loanId();
    }

    public record HardshipCaseOpened(UUID eventId, UUID caseId, UUID loanId, UUID memberId, LocalDate businessDate,
                                     String origin, int targetSeq, String level, int points, String policyVersion)
            implements HardshipEvent {
    }

    public record SignalCode(String code, int points) {
    }

    public record HardshipSignalDetected(UUID eventId, UUID caseId, UUID loanId, UUID memberId, LocalDate businessDate,
                                         String level, int points, List<SignalCode> signals) implements HardshipEvent {
    }

    public record OfferedOption(String label, String primitive, boolean recommended, Map<String, Long> summary) {
    }

    /**
     * Engagement turns this into the outreach. {@code memberMessages} (locale to text) is the grounded AI
     * paragraph or the template paragraph; {@code narrativeSource} says which.
     */
    public record HardshipOfferIssued(UUID eventId, UUID caseId, UUID loanId, UUID memberId, LocalDate businessDate,
                                      UUID offerId, int revision, LocalDate expiresOn, List<OfferedOption> options,
                                      Map<String, String> memberMessages, String narrativeSource, boolean proactive,
                                      boolean reminder) implements HardshipEvent {
    }

    public record HardshipOptionChosen(UUID eventId, UUID caseId, UUID loanId, UUID memberId, LocalDate businessDate,
                                       UUID offerId, String optionLabel, String tier, String snapshotHash)
            implements HardshipEvent {
    }

    public record HardshipDecisionMade(UUID eventId, UUID caseId, UUID loanId, UUID memberId, LocalDate businessDate,
                                       String decision, String tier, String reasonCode) implements HardshipEvent {
    }

    public record HardshipApplied(UUID eventId, UUID caseId, UUID loanId, UUID memberId, LocalDate businessDate,
                                  int scheduleVersion, String primitive, String approvalRef, List<Integer> instalmentsOnPlan)
            implements HardshipEvent {
    }

    public record HardshipCaseClosed(UUID eventId, UUID caseId, UUID loanId, UUID memberId, LocalDate businessDate,
                                     String closedReason) implements HardshipEvent {
    }

    /** Asks the Guardian for a brief and an outreach paragraph. Hardship never waits for it: templates cover a miss. */
    public record GuardianRunRequested(UUID eventId, UUID caseId, UUID loanId, String mode) implements HardshipEvent {
    }
}
