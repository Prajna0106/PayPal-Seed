package com.paypalseed.payments.infrastructure.persistence;

import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.PAYOUT;
import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.PAYOUT_TRANSITION;

import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import com.paypalseed.payments.infrastructure.persistence.jooq.tables.records.PayoutRecord;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.persistence.PgSql;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.UpdateSetMoreStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** {@code payments.payout}: the current state of each payout; {@code payout_transition}: how it got there. */
@Repository
public class PayoutRepository {

    public record Payout(UUID id, String referenceId, String senderBatchId, long amountCents, RecipientType recipientType,
                         String receiver, PayoutWallet wallet, String note, PayoutStatus status, String paypalBatchId,
                         String paypalItemId, String paypalTransactionId, long feeCents, String failureReason) {
    }

    private final DSLContext dsl;

    public PayoutRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** False when the reference already exists (a concurrent send of the same reference). */
    public boolean insert(Payout p) {
        return dsl.insertInto(PAYOUT)
                .set(PAYOUT.ID, p.id())
                .set(PAYOUT.REFERENCE_ID, p.referenceId())
                .set(PAYOUT.SENDER_BATCH_ID, p.senderBatchId())
                .set(PAYOUT.AMOUNT_CENTS, p.amountCents())
                .set(PAYOUT.RECIPIENT_TYPE, p.recipientType().name())
                .set(PAYOUT.RECEIVER, p.receiver())
                .set(PAYOUT.WALLET, p.wallet().name())
                .set(PAYOUT.NOTE, p.note())
                .set(PAYOUT.STATUS, p.status().name())
                .onConflictDoNothing()
                .execute() == 1;
    }

    public Optional<Payout> byReference(String referenceId) {
        return dsl.selectFrom(PAYOUT).where(PAYOUT.REFERENCE_ID.eq(referenceId)).fetchOptional(PayoutRepository::map);
    }

    public Optional<Payout> byReferenceForUpdate(String referenceId) {
        return dsl.selectFrom(PAYOUT).where(PAYOUT.REFERENCE_ID.eq(referenceId)).forUpdate().fetchOptional(PayoutRepository::map);
    }

    /** Finds a payout by whichever of the three ids a PayPal report carries, locked for the transaction. */
    public Optional<Payout> findForUpdate(String paypalItemId, String paypalBatchId, String senderBatchId) {
        Condition match = DSL.falseCondition();
        if (paypalItemId != null && !paypalItemId.isBlank()) {
            match = match.or(PAYOUT.PAYPAL_ITEM_ID.eq(paypalItemId));
        }
        if (paypalBatchId != null && !paypalBatchId.isBlank()) {
            match = match.or(PAYOUT.PAYPAL_BATCH_ID.eq(paypalBatchId));
        }
        if (senderBatchId != null && !senderBatchId.isBlank()) {
            match = match.or(PAYOUT.SENDER_BATCH_ID.eq(senderBatchId));
        }
        return dsl.selectFrom(PAYOUT).where(match).limit(1).forUpdate().fetchOptional(PayoutRepository::map);
    }

    /** Sets the status and fills in any PayPal id or fee learned; ids already known are kept. */
    public void update(UUID id, PayoutStatus status, String paypalBatchId, String paypalItemId, String paypalTransactionId,
                       long feeCents, String failureReason) {
        UpdateSetMoreStep<PayoutRecord> step = dsl.update(PAYOUT)
                .set(PAYOUT.STATUS, status.name())
                .set(PAYOUT.UPDATED_AT, PgSql.now());
        if (paypalBatchId != null) {
            step = step.set(PAYOUT.PAYPAL_BATCH_ID, DSL.coalesce(PAYOUT.PAYPAL_BATCH_ID, DSL.val(paypalBatchId)));
        }
        if (paypalItemId != null) {
            step = step.set(PAYOUT.PAYPAL_ITEM_ID, DSL.coalesce(PAYOUT.PAYPAL_ITEM_ID, DSL.val(paypalItemId)));
        }
        if (paypalTransactionId != null) {
            step = step.set(PAYOUT.PAYPAL_TRANSACTION_ID, paypalTransactionId);
        }
        if (feeCents > 0) {
            step = step.set(PAYOUT.FEE_CENTS, feeCents);
        }
        if (failureReason != null) {
            step = step.set(PAYOUT.FAILURE_REASON, failureReason);
        }
        step.where(PAYOUT.ID.eq(id)).execute();
    }

    public void addTransition(UUID payoutId, PayoutStatus from, PayoutStatus to, String reason, String source,
                              String paypalEventId) {
        dsl.insertInto(PAYOUT_TRANSITION)
                .set(PAYOUT_TRANSITION.ID, Ids.newId())
                .set(PAYOUT_TRANSITION.PAYOUT_ID, payoutId)
                .set(PAYOUT_TRANSITION.FROM_STATUS, from == null ? null : from.name())
                .set(PAYOUT_TRANSITION.TO_STATUS, to.name())
                .set(PAYOUT_TRANSITION.REASON, reason)
                .set(PAYOUT_TRANSITION.SOURCE, source)
                .set(PAYOUT_TRANSITION.PAYPAL_EVENT_ID, paypalEventId)
                .execute();
    }

    private static Payout map(PayoutRecord r) {
        return new Payout(r.getId(), r.getReferenceId(), r.getSenderBatchId(), r.getAmountCents(),
                RecipientType.valueOf(r.getRecipientType()), r.getReceiver(), PayoutWallet.valueOf(r.getWallet()), r.getNote(),
                PayoutStatus.valueOf(r.getStatus()), r.getPaypalBatchId(), r.getPaypalItemId(), r.getPaypalTransactionId(),
                r.getFeeCents(), r.getFailureReason());
    }
}
