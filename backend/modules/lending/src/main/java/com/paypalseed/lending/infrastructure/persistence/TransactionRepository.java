package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.LOAN_TRANSACTION;
import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.PENDING_PAYMENT;

import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.lending.domain.model.LoanTransaction.Type;
import com.paypalseed.lending.infrastructure.persistence.jooq.tables.records.LoanTransactionRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private final DSLContext dsl;

    public TransactionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(LoanTransaction t) {
        dsl.insertInto(LOAN_TRANSACTION)
                .set(LOAN_TRANSACTION.ID, t.id())
                .set(LOAN_TRANSACTION.LOAN_ID, t.loanId())
                .set(LOAN_TRANSACTION.TYPE, t.type().name())
                .set(LOAN_TRANSACTION.SOURCE, t.source().name())
                .set(LOAN_TRANSACTION.PAYPAL_TXN_ID, t.paypalTxnId())
                .set(LOAN_TRANSACTION.REVERSES_ID, t.reversesId())
                .set(LOAN_TRANSACTION.AMOUNT_CENTS, t.amountCents())
                .set(LOAN_TRANSACTION.FEE_CENTS, t.feeCents())
                .set(LOAN_TRANSACTION.PAYER_REF, t.payerRef())
                .set(LOAN_TRANSACTION.OCCURRED_AT, t.occurredAt())
                .set(LOAN_TRANSACTION.EFFECTIVE_DATE, t.effectiveDate())
                .set(LOAN_TRANSACTION.JOURNAL_ENTRY_ID, t.journalEntryId())
                .set(LOAN_TRANSACTION.CAUSED_BY, t.causedBy())
                .execute();
    }

    public Optional<LoanTransaction> byPaypalTxnId(String paypalTxnId) {
        return dsl.selectFrom(LOAN_TRANSACTION).where(LOAN_TRANSACTION.PAYPAL_TXN_ID.eq(paypalTxnId))
                .fetchOptional(TransactionRepository::map);
    }

    /** All money events of a loan in fold order. */
    public List<LoanTransaction> forLoan(UUID loanId) {
        return dsl.selectFrom(LOAN_TRANSACTION).where(LOAN_TRANSACTION.LOAN_ID.eq(loanId))
                .orderBy(LOAN_TRANSACTION.EFFECTIVE_DATE, LOAN_TRANSACTION.ID)
                .fetch(TransactionRepository::map);
    }

    private static LoanTransaction map(LoanTransactionRecord r) {
        return new LoanTransaction(
                r.getId(),
                r.getLoanId(),
                Type.valueOf(r.getType()),
                Source.valueOf(r.getSource()),
                r.getPaypalTxnId(),
                r.getReversesId(),
                r.getAmountCents(),
                r.getFeeCents(),
                r.getPayerRef(),
                r.getOccurredAt(),
                r.getEffectiveDate(),
                r.getJournalEntryId(),
                r.getCausedBy());
    }

    /** Pending PayPal money (for example an eCheck) that holds off nudges and retries until it settles. */
    public void recordPending(UUID loanId, String paypalTxnId, long amountCents, Instant since) {
        dsl.insertInto(PENDING_PAYMENT)
                .set(PENDING_PAYMENT.PAYPAL_TXN_ID, paypalTxnId)
                .set(PENDING_PAYMENT.LOAN_ID, loanId)
                .set(PENDING_PAYMENT.AMOUNT_CENTS, amountCents)
                .set(PENDING_PAYMENT.SINCE, since)
                .onConflict(PENDING_PAYMENT.PAYPAL_TXN_ID).doNothing()
                .execute();
    }

    public void clearPending(String paypalTxnId) {
        dsl.deleteFrom(PENDING_PAYMENT).where(PENDING_PAYMENT.PAYPAL_TXN_ID.eq(paypalTxnId)).execute();
    }

    public long pendingCents(UUID loanId) {
        return dsl.select(DSL.coalesce(DSL.sum(PENDING_PAYMENT.AMOUNT_CENTS), BigDecimal.ZERO))
                .from(PENDING_PAYMENT)
                .where(PENDING_PAYMENT.LOAN_ID.eq(loanId))
                .fetchSingle()
                .value1()
                .longValueExact();
    }
}
