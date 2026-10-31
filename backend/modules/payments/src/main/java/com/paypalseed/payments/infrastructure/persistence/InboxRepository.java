package com.paypalseed.payments.infrastructure.persistence;

import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.WEBHOOK_INBOX;

import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/** {@code payments.webhook_inbox}: verified PayPal webhooks, stored before acknowledging (fast 200). */
@Repository
public class InboxRepository {

    public record InboxEvent(UUID id, String paypalEventId, String eventType, String payload, int attempts) {
    }

    private final DSLContext dsl;

    public InboxRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Returns false for a duplicate delivery. */
    public boolean insert(String paypalEventId, String eventType, String payloadJson) {
        return dsl.insertInto(WEBHOOK_INBOX)
                .set(WEBHOOK_INBOX.ID, Ids.newId())
                .set(WEBHOOK_INBOX.PAYPAL_EVENT_ID, paypalEventId)
                .set(WEBHOOK_INBOX.EVENT_TYPE, eventType)
                .set(WEBHOOK_INBOX.PAYLOAD, JSONB.valueOf(payloadJson))
                .set(WEBHOOK_INBOX.STATUS, "RECEIVED")
                .onConflict(WEBHOOK_INBOX.PAYPAL_EVENT_ID).doNothing()
                .execute() == 1;
    }

    /** Claims due work; rows stay locked until the claiming transaction ends, so workers never double-process. */
    public List<InboxEvent> claimDue(int limit) {
        return dsl.selectFrom(WEBHOOK_INBOX)
                .where(WEBHOOK_INBOX.STATUS.in("RECEIVED", "PARKED"))
                .and(WEBHOOK_INBOX.NEXT_ATTEMPT_AT.le(PgSql.now()))
                .orderBy(WEBHOOK_INBOX.RECEIVED_AT)
                .limit(limit)
                .forUpdate().skipLocked()
                .fetch(r -> new InboxEvent(r.getId(), r.getPaypalEventId(), r.getEventType(), r.getPayload().data(),
                        r.getAttempts()));
    }

    public void done(UUID id, String status) {
        dsl.update(WEBHOOK_INBOX)
                .set(WEBHOOK_INBOX.STATUS, status)
                .set(WEBHOOK_INBOX.ATTEMPTS, WEBHOOK_INBOX.ATTEMPTS.plus(1))
                .set(WEBHOOK_INBOX.PROCESSED_AT, PgSql.now())
                .set(WEBHOOK_INBOX.WAIT_FOR, (String) null)
                .set(WEBHOOK_INBOX.LAST_ERROR, (String) null)
                .where(WEBHOOK_INBOX.ID.eq(id))
                .execute();
    }

    public void park(UUID id, String waitFor, Instant retryAt) {
        dsl.update(WEBHOOK_INBOX)
                .set(WEBHOOK_INBOX.STATUS, "PARKED")
                .set(WEBHOOK_INBOX.ATTEMPTS, WEBHOOK_INBOX.ATTEMPTS.plus(1))
                .set(WEBHOOK_INBOX.WAIT_FOR, waitFor)
                .set(WEBHOOK_INBOX.NEXT_ATTEMPT_AT, retryAt)
                .where(WEBHOOK_INBOX.ID.eq(id))
                .execute();
    }

    public void failed(UUID id, String error, Instant retryAt, boolean giveUp) {
        dsl.update(WEBHOOK_INBOX)
                .set(WEBHOOK_INBOX.STATUS, giveUp ? "FAILED" : "RECEIVED")
                .set(WEBHOOK_INBOX.ATTEMPTS, WEBHOOK_INBOX.ATTEMPTS.plus(1))
                .set(WEBHOOK_INBOX.LAST_ERROR, error)
                .set(WEBHOOK_INBOX.NEXT_ATTEMPT_AT, retryAt)
                .where(WEBHOOK_INBOX.ID.eq(id))
                .execute();
    }

    /** Wakes events parked on a now-known parent (for example a subscription that just activated). */
    public int wake(String waitFor) {
        return dsl.update(WEBHOOK_INBOX)
                .set(WEBHOOK_INBOX.NEXT_ATTEMPT_AT, PgSql.now())
                .where(WEBHOOK_INBOX.STATUS.eq("PARKED"))
                .and(WEBHOOK_INBOX.WAIT_FOR.eq(waitFor))
                .execute();
    }

    public String status(String paypalEventId) {
        return dsl.select(WEBHOOK_INBOX.STATUS)
                .from(WEBHOOK_INBOX)
                .where(WEBHOOK_INBOX.PAYPAL_EVENT_ID.eq(paypalEventId))
                .fetchOptional(WEBHOOK_INBOX.STATUS)
                .orElse("ABSENT");
    }
}
