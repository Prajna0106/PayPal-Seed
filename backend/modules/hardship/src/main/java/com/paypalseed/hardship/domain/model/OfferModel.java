package com.paypalseed.hardship.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An offer and its frozen options, as stored. */
public final class OfferModel {

    private OfferModel() {
    }

    public record OfferRow(UUID id, UUID caseId, int revision, int baseVersion, LocalDate issuedOn, LocalDate expiresOn,
                           String recommended, String status) {
    }

    /** One proposed instalment of an option's preview: what the snapshot hash covers. */
    public record ProposedRow(int seq, LocalDate dueDate, long amountCents, long remainingCents) {
    }

    public record OptionRow(UUID id, UUID offerId, String label, String primitive, Map<String, Integer> args,
                            List<ProposedRow> proposed, Map<String, Long> summary, String snapshotHash,
                            BigDecimal meanDelayDays, long maxInstalmentCents, Integer burdenBps, Boolean sustainable,
                            boolean recommended, String tier, List<String> warnings) {
    }

    public record ChoiceRow(UUID id, UUID caseId, UUID offerId, UUID optionId, String kind, String channel,
                            String idempotencyKey) {
    }
}
