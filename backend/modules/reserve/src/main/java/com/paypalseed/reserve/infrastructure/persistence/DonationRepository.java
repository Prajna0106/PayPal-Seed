package com.paypalseed.reserve.infrastructure.persistence;

import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.DONATION;
import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.DONATION_INTENT;

import com.paypalseed.reserve.infrastructure.persistence.jooq.tables.records.DonationIntentRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Gifts (append-only) and the donor's choices recorded before PayPal. */
@Repository
public class DonationRepository {

    public record DonationRow(UUID id, String captureId, long amountCents, long feeCents, boolean anonymous,
                              String displayName, UUID journalEntryId, LocalDate businessDate) {
    }

    public record IntentRow(UUID id, String idempotencyKey, String requestHash, boolean anonymous, String displayName) {
    }

    private final DSLContext dsl;

    DonationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<DonationRow> byCapture(String captureId) {
        return dsl.selectFrom(DONATION).where(DONATION.CAPTURE_ID.eq(captureId))
                .fetchOptional(r -> new DonationRow(r.getId(), r.getCaptureId(), r.getAmountCents(), r.getFeeCents(),
                        r.getAnonymous(), r.getDisplayName(), r.getJournalEntryId(), r.getBusinessDate()));
    }

    public void insert(DonationRow d) {
        dsl.insertInto(DONATION)
                .set(DONATION.ID, d.id())
                .set(DONATION.CAPTURE_ID, d.captureId())
                .set(DONATION.AMOUNT_CENTS, d.amountCents())
                .set(DONATION.FEE_CENTS, d.feeCents())
                .set(DONATION.ANONYMOUS, d.anonymous())
                .set(DONATION.DISPLAY_NAME, d.displayName())
                .set(DONATION.JOURNAL_ENTRY_ID, d.journalEntryId())
                .set(DONATION.BUSINESS_DATE, d.businessDate())
                .execute();
    }

    /** Names of the latest donors who chose to be shown; nothing else about them leaves the table. */
    public List<String> recentSupporters(int limit) {
        return dsl.select(DONATION.DISPLAY_NAME).from(DONATION)
                .where(DONATION.ANONYMOUS.isFalse())
                .orderBy(DONATION.CREATED_AT.desc(), DONATION.ID.desc())
                .limit(limit)
                .fetch(DONATION.DISPLAY_NAME);
    }

    /** False when the idempotency key already exists (a concurrent or repeated request); the caller re-reads. */
    public boolean insertIntent(IntentRow i) {
        return dsl.insertInto(DONATION_INTENT)
                .set(DONATION_INTENT.ID, i.id())
                .set(DONATION_INTENT.IDEMPOTENCY_KEY, i.idempotencyKey())
                .set(DONATION_INTENT.REQUEST_HASH, i.requestHash())
                .set(DONATION_INTENT.ANONYMOUS, i.anonymous())
                .set(DONATION_INTENT.DISPLAY_NAME, i.displayName())
                .onConflict(DONATION_INTENT.IDEMPOTENCY_KEY).doNothing()
                .execute() == 1;
    }

    public Optional<IntentRow> intentByKey(String key) {
        return dsl.selectFrom(DONATION_INTENT).where(DONATION_INTENT.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(DonationRepository::toIntent);
    }

    public Optional<IntentRow> intent(UUID id) {
        return dsl.selectFrom(DONATION_INTENT).where(DONATION_INTENT.ID.eq(id)).fetchOptional(DonationRepository::toIntent);
    }

    private static IntentRow toIntent(DonationIntentRecord r) {
        return new IntentRow(r.getId(), r.getIdempotencyKey(), r.getRequestHash(), r.getAnonymous(), r.getDisplayName());
    }
}
