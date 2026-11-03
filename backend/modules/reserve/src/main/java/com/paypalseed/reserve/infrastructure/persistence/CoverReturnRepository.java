package com.paypalseed.reserve.infrastructure.persistence;

import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.COVER_RETURN;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Money handed back to the fund, one row per return, keyed by what makes it idempotent. Append-only. */
@Repository
public class CoverReturnRepository {

    public record ReturnRow(String returnRef, String kind, UUID loanId, long amountCents, UUID journalEntryId) {
    }

    private final DSLContext dsl;

    CoverReturnRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<ReturnRow> find(String returnRef) {
        return dsl.selectFrom(COVER_RETURN).where(COVER_RETURN.RETURN_REF.eq(returnRef))
                .fetchOptional(r -> new ReturnRow(r.getReturnRef(), r.getKind(), r.getLoanId(), r.getAmountCents(),
                        r.getJournalEntryId()));
    }

    public void insert(ReturnRow row, String reasonCode, LocalDate businessDate) {
        dsl.insertInto(COVER_RETURN)
                .set(COVER_RETURN.RETURN_REF, row.returnRef())
                .set(COVER_RETURN.KIND, row.kind())
                .set(COVER_RETURN.LOAN_ID, row.loanId())
                .set(COVER_RETURN.AMOUNT_CENTS, row.amountCents())
                .set(COVER_RETURN.JOURNAL_ENTRY_ID, row.journalEntryId())
                .set(COVER_RETURN.REASON_CODE, reasonCode)
                .set(COVER_RETURN.BUSINESS_DATE, businessDate)
                .execute();
    }
}
