package com.paypalseed.payments.infrastructure.persistence;

import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.SAVINGS_TOPUP;

import com.paypalseed.payments.api.dto.PaymentsDtos.FundingSource;
import com.paypalseed.payments.infrastructure.persistence.jooq.tables.records.SavingsTopupRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code payments.savings_topup}: one-off Orders that deposit into a savings goal. */
@Repository
public class TopUpRepository {

    public record TopUp(String orderId, UUID memberId, UUID goalId, long amountCents, FundingSource funding, String status,
                        String captureId) {
    }

    private final DSLContext dsl;

    public TopUpRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void record(String orderId, UUID memberId, UUID goalId, long amountCents, FundingSource funding, String status) {
        dsl.insertInto(SAVINGS_TOPUP)
                .set(SAVINGS_TOPUP.PAYPAL_ORDER_ID, orderId)
                .set(SAVINGS_TOPUP.MEMBER_ID, memberId)
                .set(SAVINGS_TOPUP.GOAL_ID, goalId)
                .set(SAVINGS_TOPUP.AMOUNT_CENTS, amountCents)
                .set(SAVINGS_TOPUP.FUNDING_SOURCE, funding.name())
                .set(SAVINGS_TOPUP.STATUS, status)
                .onConflict(SAVINGS_TOPUP.PAYPAL_ORDER_ID).doNothing()
                .execute();
    }

    public Optional<TopUp> byOrder(String orderId) {
        return dsl.selectFrom(SAVINGS_TOPUP).where(SAVINGS_TOPUP.PAYPAL_ORDER_ID.eq(orderId))
                .fetchOptional(TopUpRepository::map);
    }

    public void status(String orderId, String status) {
        dsl.update(SAVINGS_TOPUP)
                .set(SAVINGS_TOPUP.STATUS, status)
                .set(SAVINGS_TOPUP.UPDATED_AT, PgSql.now())
                .where(SAVINGS_TOPUP.PAYPAL_ORDER_ID.eq(orderId))
                .execute();
    }

    public boolean isCapture(String captureId) {
        return dsl.fetchExists(SAVINGS_TOPUP, SAVINGS_TOPUP.PAYPAL_CAPTURE_ID.eq(captureId));
    }

    /** True the first time a capture id is attached to the order; a replay, or the other of API and webhook, gets false. */
    public boolean claimCapture(String orderId, String captureId) {
        return dsl.update(SAVINGS_TOPUP)
                .set(SAVINGS_TOPUP.PAYPAL_CAPTURE_ID, captureId)
                .set(SAVINGS_TOPUP.STATUS, "COMPLETED")
                .set(SAVINGS_TOPUP.UPDATED_AT, PgSql.now())
                .where(SAVINGS_TOPUP.PAYPAL_ORDER_ID.eq(orderId)).and(SAVINGS_TOPUP.PAYPAL_CAPTURE_ID.isNull())
                .execute() == 1;
    }

    private static TopUp map(SavingsTopupRecord r) {
        return new TopUp(r.getPaypalOrderId(), r.getMemberId(), r.getGoalId(), r.getAmountCents(),
                FundingSource.valueOf(r.getFundingSource()), r.getStatus(), r.getPaypalCaptureId());
    }
}
