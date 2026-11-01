package com.paypalseed.fraud.infrastructure.persistence;

import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.REVIEW;

import com.paypalseed.fraud.api.dto.FraudDtos.ReviewDecision;
import com.paypalseed.fraud.infrastructure.persistence.jooq.tables.records.ReviewRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** The officer's decision on a flag, one per flag, append-only. */
@Repository
public class ReviewRepository {

    public record ReviewRow(UUID id, UUID flagId, ReviewDecision decision, String note, UUID staffId, String idempotencyKey,
                            String requestHash, Instant createdAt) {
    }

    private final DSLContext dsl;

    ReviewRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(UUID id, UUID flagId, ReviewDecision decision, String note, UUID staffId, String idempotencyKey,
                       String requestHash) {
        dsl.insertInto(REVIEW)
                .set(REVIEW.ID, id)
                .set(REVIEW.FLAG_ID, flagId)
                .set(REVIEW.DECISION, decision.name())
                .set(REVIEW.NOTE, note)
                .set(REVIEW.STAFF_ID, staffId)
                .set(REVIEW.IDEMPOTENCY_KEY, idempotencyKey)
                .set(REVIEW.REQUEST_HASH, requestHash)
                .execute();
    }

    public Optional<ReviewRow> ofFlag(UUID flagId) {
        return dsl.selectFrom(REVIEW).where(REVIEW.FLAG_ID.eq(flagId)).fetchOptional(ReviewRepository::row);
    }

    public Optional<ReviewRow> byKey(String idempotencyKey) {
        return dsl.selectFrom(REVIEW).where(REVIEW.IDEMPOTENCY_KEY.eq(idempotencyKey)).fetchOptional(ReviewRepository::row);
    }

    private static ReviewRow row(ReviewRecord r) {
        return new ReviewRow(r.getId(), r.getFlagId(), ReviewDecision.valueOf(r.getDecision()), r.getNote(), r.getStaffId(),
                r.getIdempotencyKey(), r.getRequestHash(), r.getCreatedAt());
    }
}
