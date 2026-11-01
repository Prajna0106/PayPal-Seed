package com.paypalseed.hardship.infrastructure.persistence;

import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.CHOICE;
import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.OFFER;
import static com.paypalseed.hardship.infrastructure.persistence.jooq.Tables.OFFER_OPTION;

import com.paypalseed.hardship.domain.model.OfferModel.ChoiceRow;
import com.paypalseed.hardship.domain.model.OfferModel.OfferRow;
import com.paypalseed.hardship.domain.model.OfferModel.OptionRow;
import com.paypalseed.hardship.infrastructure.persistence.jooq.tables.records.OfferOptionRecord;
import com.paypalseed.hardship.infrastructure.persistence.jooq.tables.records.OfferRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Offers, their frozen options and the member's recorded consent. */
@Repository
public class OfferRepository {

    private final DSLContext dsl;
    private final JsonColumns json;

    OfferRepository(DSLContext dsl, JsonColumns json) {
        this.dsl = dsl;
        this.json = json;
    }

    public void insert(OfferRow offer, List<OptionRow> options) {
        dsl.insertInto(OFFER)
                .set(OFFER.ID, offer.id())
                .set(OFFER.CASE_ID, offer.caseId())
                .set(OFFER.REVISION, offer.revision())
                .set(OFFER.BASE_VERSION, offer.baseVersion())
                .set(OFFER.ISSUED_ON, offer.issuedOn())
                .set(OFFER.EXPIRES_ON, offer.expiresOn())
                .set(OFFER.RECOMMENDED, offer.recommended())
                .set(OFFER.STATUS, offer.status())
                .execute();
        for (OptionRow o : options) {
            dsl.insertInto(OFFER_OPTION)
                    .set(OFFER_OPTION.ID, o.id())
                    .set(OFFER_OPTION.OFFER_ID, offer.id())
                    .set(OFFER_OPTION.LABEL, o.label())
                    .set(OFFER_OPTION.PRIMITIVE, o.primitive())
                    .set(OFFER_OPTION.ARGS, json.write(o.args()))
                    .set(OFFER_OPTION.PROPOSED, json.writeRows(o.proposed()))
                    .set(OFFER_OPTION.SUMMARY, json.write(o.summary()))
                    .set(OFFER_OPTION.SNAPSHOT_HASH, o.snapshotHash())
                    .set(OFFER_OPTION.MEAN_DELAY_DAYS, o.meanDelayDays())
                    .set(OFFER_OPTION.MAX_INSTALMENT_CENTS, o.maxInstalmentCents())
                    .set(OFFER_OPTION.BURDEN_BPS, o.burdenBps())
                    .set(OFFER_OPTION.SUSTAINABLE, o.sustainable())
                    .set(OFFER_OPTION.RECOMMENDED, o.recommended())
                    .set(OFFER_OPTION.TIER, o.tier())
                    .set(OFFER_OPTION.WARNINGS, json.write(o.warnings()))
                    .execute();
        }
    }

    public Optional<OfferRow> active(UUID caseId) {
        return dsl.selectFrom(OFFER).where(OFFER.CASE_ID.eq(caseId).and(OFFER.STATUS.eq("ACTIVE")))
                .fetchOptional(OfferRepository::map);
    }

    public Optional<OfferRow> find(UUID offerId) {
        return dsl.selectFrom(OFFER).where(OFFER.ID.eq(offerId)).fetchOptional(OfferRepository::map);
    }

    /** The newest offer of the case whatever its status (the one a choice was made on). */
    public Optional<OfferRow> latest(UUID caseId) {
        return dsl.selectFrom(OFFER).where(OFFER.CASE_ID.eq(caseId)).orderBy(OFFER.REVISION.desc()).limit(1)
                .fetchOptional(OfferRepository::map);
    }

    public void setStatus(UUID offerId, String status) {
        dsl.update(OFFER).set(OFFER.STATUS, status).where(OFFER.ID.eq(offerId)).execute();
    }

    public List<OptionRow> options(UUID offerId) {
        return dsl.selectFrom(OFFER_OPTION).where(OFFER_OPTION.OFFER_ID.eq(offerId)).orderBy(OFFER_OPTION.LABEL)
                .fetch(this::map);
    }

    public Optional<OptionRow> option(UUID optionId) {
        return dsl.selectFrom(OFFER_OPTION).where(OFFER_OPTION.ID.eq(optionId)).fetchOptional(this::map);
    }

    /** Returns false when the key was used before or the offer already has a choice. */
    public boolean insertChoice(ChoiceRow c, UUID memberId, String consentTextVersion) {
        return dsl.insertInto(CHOICE)
                .set(CHOICE.ID, c.id())
                .set(CHOICE.CASE_ID, c.caseId())
                .set(CHOICE.OFFER_ID, c.offerId())
                .set(CHOICE.OPTION_ID, c.optionId())
                .set(CHOICE.KIND, c.kind())
                .set(CHOICE.MEMBER_ID, memberId)
                .set(CHOICE.CHANNEL, c.channel())
                .set(CHOICE.CONSENT_TEXT_VERSION, consentTextVersion)
                .set(CHOICE.IDEMPOTENCY_KEY, c.idempotencyKey())
                .onConflictDoNothing()
                .execute() == 1;
    }

    public Optional<ChoiceRow> choiceByKey(String key) {
        return dsl.selectFrom(CHOICE).where(CHOICE.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(r -> new ChoiceRow(r.getId(), r.getCaseId(), r.getOfferId(), r.getOptionId(), r.getKind(),
                        r.getChannel(), r.getIdempotencyKey()));
    }

    public Optional<ChoiceRow> latestChoice(UUID caseId) {
        return dsl.selectFrom(CHOICE).where(CHOICE.CASE_ID.eq(caseId)).orderBy(CHOICE.CHOSEN_AT.desc()).limit(1)
                .fetchOptional(r -> new ChoiceRow(r.getId(), r.getCaseId(), r.getOfferId(), r.getOptionId(), r.getKind(),
                        r.getChannel(), r.getIdempotencyKey()));
    }

    private static OfferRow map(OfferRecord r) {
        return new OfferRow(r.getId(), r.getCaseId(), r.getRevision(), r.getBaseVersion(), r.getIssuedOn(), r.getExpiresOn(),
                r.getRecommended(), r.getStatus());
    }

    private OptionRow map(OfferOptionRecord r) {
        return new OptionRow(r.getId(), r.getOfferId(), r.getLabel(), r.getPrimitive(), json.ints(r.getArgs()),
                json.rows(r.getProposed()), json.longs(r.getSummary()), r.getSnapshotHash(), r.getMeanDelayDays(),
                r.getMaxInstalmentCents(), r.getBurdenBps(), r.getSustainable(), r.getRecommended(), r.getTier(),
                json.strings(r.getWarnings()));
    }
}
