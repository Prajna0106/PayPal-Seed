package com.paypalseed.payments.infrastructure.persistence;

import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.COLLECTION_MANDATE;

import com.paypalseed.engines.schedule.MandateReconciliation.MandateStatus;
import com.paypalseed.payments.infrastructure.persistence.jooq.tables.records.CollectionMandateRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class MandateRepository {

    public record Mandate(UUID id, UUID loanId, UUID memberId, String subscriptionId, MandateStatus status, long quantity,
                          LocalDate nextBillingDate, String approveUrl) {
    }

    private static final String[] LIVE_STATUSES = {"APPROVAL_PENDING", "ACTIVE", "SUSPENDED"};

    private final DSLContext dsl;

    public MandateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The live or pending mandate of a loan, locked for the rest of the transaction. */
    public Optional<Mandate> liveForUpdate(UUID loanId) {
        return dsl.selectFrom(COLLECTION_MANDATE)
                .where(COLLECTION_MANDATE.LOAN_ID.eq(loanId))
                .and(COLLECTION_MANDATE.STATUS.in(LIVE_STATUSES))
                .forUpdate()
                .fetchOptional(MandateRepository::map);
    }

    public Optional<Mandate> live(UUID loanId) {
        return dsl.selectFrom(COLLECTION_MANDATE)
                .where(COLLECTION_MANDATE.LOAN_ID.eq(loanId))
                .and(COLLECTION_MANDATE.STATUS.in(LIVE_STATUSES))
                .fetchOptional(MandateRepository::map);
    }

    public Optional<Mandate> latest(UUID loanId) {
        return dsl.selectFrom(COLLECTION_MANDATE)
                .where(COLLECTION_MANDATE.LOAN_ID.eq(loanId))
                .orderBy(COLLECTION_MANDATE.CREATED_AT.desc())
                .limit(1)
                .fetchOptional(MandateRepository::map);
    }

    public Optional<Mandate> bySubscription(String subscriptionId) {
        return dsl.selectFrom(COLLECTION_MANDATE)
                .where(COLLECTION_MANDATE.PAYPAL_SUBSCRIPTION_ID.eq(subscriptionId))
                .fetchOptional(MandateRepository::map);
    }

    public void insert(Mandate m) {
        dsl.insertInto(COLLECTION_MANDATE)
                .set(COLLECTION_MANDATE.ID, m.id())
                .set(COLLECTION_MANDATE.LOAN_ID, m.loanId())
                .set(COLLECTION_MANDATE.MEMBER_ID, m.memberId())
                .set(COLLECTION_MANDATE.PAYPAL_SUBSCRIPTION_ID, m.subscriptionId())
                .set(COLLECTION_MANDATE.STATUS, m.status().name())
                .set(COLLECTION_MANDATE.QUANTITY, Math.toIntExact(m.quantity()))
                .set(COLLECTION_MANDATE.NEXT_BILLING_DATE, m.nextBillingDate())
                .set(COLLECTION_MANDATE.APPROVE_URL, m.approveUrl())
                .set(COLLECTION_MANDATE.OBSERVED_AT, PgSql.now())
                .execute();
    }

    /** Records what PayPal reports. Returns the previous status. */
    public void observe(UUID id, MandateStatus status, long quantity, LocalDate nextBillingDate) {
        dsl.update(COLLECTION_MANDATE)
                .set(COLLECTION_MANDATE.STATUS, status.name())
                .set(COLLECTION_MANDATE.QUANTITY, Math.toIntExact(quantity))
                .set(COLLECTION_MANDATE.NEXT_BILLING_DATE, nextBillingDate)
                .set(COLLECTION_MANDATE.OBSERVED_AT, PgSql.now())
                .set(COLLECTION_MANDATE.UPDATED_AT, PgSql.now())
                .where(COLLECTION_MANDATE.ID.eq(id))
                .execute();
    }

    public void recordDesired(UUID id, String desiredJson) {
        dsl.update(COLLECTION_MANDATE)
                .set(COLLECTION_MANDATE.LAST_DESIRED, JSONB.valueOf(desiredJson))
                .set(COLLECTION_MANDATE.UPDATED_AT, PgSql.now())
                .where(COLLECTION_MANDATE.ID.eq(id))
                .execute();
    }

    private static Mandate map(CollectionMandateRecord r) {
        return new Mandate(r.getId(), r.getLoanId(), r.getMemberId(), r.getPaypalSubscriptionId(),
                MandateStatus.valueOf(r.getStatus()), r.getQuantity(), r.getNextBillingDate(), r.getApproveUrl());
    }
}
