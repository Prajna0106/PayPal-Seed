package com.paypalseed.payments.infrastructure.persistence;

import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.PAYPAL_COMMAND;

import com.paypalseed.platform.persistence.PgSql;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/** {@code payments.paypal_command}: the audit and idempotency log of every PayPal write. */
@Repository
public class CommandRepository {

    public record Command(UUID id, String kind, UUID aggregateId, String idempotencyKey, String requestHash, String status,
                          int attempts, String response) {
    }

    private final DSLContext dsl;

    public CommandRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Command> byKey(String key) {
        return dsl.selectFrom(PAYPAL_COMMAND)
                .where(PAYPAL_COMMAND.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(r -> new Command(r.getId(), r.getKind(), r.getAggregateId(), r.getIdempotencyKey(),
                        r.getRequestHash(), r.getStatus(), r.getAttempts(),
                        r.getResponse() == null ? null : r.getResponse().data()));
    }

    public void insertPending(UUID id, String kind, UUID aggregateId, String key, String requestHash) {
        dsl.insertInto(PAYPAL_COMMAND)
                .set(PAYPAL_COMMAND.ID, id)
                .set(PAYPAL_COMMAND.KIND, kind)
                .set(PAYPAL_COMMAND.AGGREGATE_ID, aggregateId)
                .set(PAYPAL_COMMAND.IDEMPOTENCY_KEY, key)
                .set(PAYPAL_COMMAND.REQUEST_HASH, requestHash)
                .set(PAYPAL_COMMAND.STATUS, "PENDING")
                .onConflict(PAYPAL_COMMAND.IDEMPOTENCY_KEY).doNothing()
                .execute();
    }

    public void succeeded(String key, String responseJson) {
        dsl.update(PAYPAL_COMMAND)
                .set(PAYPAL_COMMAND.STATUS, "SUCCEEDED")
                .set(PAYPAL_COMMAND.ATTEMPTS, PAYPAL_COMMAND.ATTEMPTS.plus(1))
                .set(PAYPAL_COMMAND.RESPONSE, JSONB.valueOf(responseJson))
                .set(PAYPAL_COMMAND.LAST_ERROR, (String) null)
                .set(PAYPAL_COMMAND.UPDATED_AT, PgSql.now())
                .where(PAYPAL_COMMAND.IDEMPOTENCY_KEY.eq(key))
                .execute();
    }

    public void failed(String key, boolean retryable, String error) {
        dsl.update(PAYPAL_COMMAND)
                .set(PAYPAL_COMMAND.STATUS, retryable ? "FAILED_RETRYABLE" : "FAILED_FINAL")
                .set(PAYPAL_COMMAND.ATTEMPTS, PAYPAL_COMMAND.ATTEMPTS.plus(1))
                .set(PAYPAL_COMMAND.LAST_ERROR, error)
                .set(PAYPAL_COMMAND.UPDATED_AT, PgSql.now())
                .where(PAYPAL_COMMAND.IDEMPOTENCY_KEY.eq(key))
                .execute();
    }

    /** True when Seed itself asked PayPal to cancel this loan's subscription. */
    public boolean cancelRequested(UUID loanId) {
        return dsl.fetchExists(PAYPAL_COMMAND,
                PAYPAL_COMMAND.AGGREGATE_ID.eq(loanId)
                        .and(PAYPAL_COMMAND.KIND.eq("CANCEL_SUBSCRIPTION"))
                        .and(PAYPAL_COMMAND.STATUS.ne("FAILED_FINAL")));
    }
}
