package com.paypalseed.reserve.infrastructure.persistence;

import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.LOAN_EXPOSURE;
import static org.jooq.impl.DSL.excluded;

import com.paypalseed.platform.persistence.PgSql;
import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** What members owe, per loan, kept from lending's events. */
@Repository
public class ExposureRepository {

    private final DSLContext dsl;

    ExposureRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Sets the outstanding balance unless a newer event already did (event ids are time-ordered). */
    public void set(UUID loanId, long outstandingCents, UUID eventId) {
        dsl.insertInto(LOAN_EXPOSURE)
                .set(LOAN_EXPOSURE.LOAN_ID, loanId)
                .set(LOAN_EXPOSURE.OUTSTANDING_CENTS, outstandingCents)
                .set(LOAN_EXPOSURE.LAST_EVENT_ID, eventId)
                .onConflict(LOAN_EXPOSURE.LOAN_ID)
                .doUpdate()
                .set(LOAN_EXPOSURE.OUTSTANDING_CENTS, excluded(LOAN_EXPOSURE.OUTSTANDING_CENTS))
                .set(LOAN_EXPOSURE.LAST_EVENT_ID, excluded(LOAN_EXPOSURE.LAST_EVENT_ID))
                .set(LOAN_EXPOSURE.UPDATED_AT, PgSql.now())
                .where(LOAN_EXPOSURE.LAST_EVENT_ID.lt(excluded(LOAN_EXPOSURE.LAST_EVENT_ID)))
                .execute();
    }

    public long total() {
        BigDecimal sum = dsl.select(DSL.coalesce(DSL.sum(LOAN_EXPOSURE.OUTSTANDING_CENTS), BigDecimal.ZERO))
                .from(LOAN_EXPOSURE)
                .where(LOAN_EXPOSURE.OUTSTANDING_CENTS.gt(0L))
                .fetchOne(0, BigDecimal.class);
        return sum.longValueExact();
    }
}
