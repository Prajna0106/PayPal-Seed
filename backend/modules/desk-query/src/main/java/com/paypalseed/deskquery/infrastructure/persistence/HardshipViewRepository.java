package com.paypalseed.deskquery.infrastructure.persistence;

import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.HARDSHIP_CASE_VIEW;

import com.paypalseed.platform.persistence.PgSql;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Hardship cases as the desk counts them. CLOSED is terminal, so no ordering of events can reopen a closed case. */
@Repository
public class HardshipViewRepository {

    private final DSLContext dsl;

    public HardshipViewRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void opened(UUID caseId, UUID loanId, UUID memberRef, String level, String origin, LocalDate openedOn) {
        dsl.insertInto(HARDSHIP_CASE_VIEW)
                .set(HARDSHIP_CASE_VIEW.CASE_ID, caseId)
                .set(HARDSHIP_CASE_VIEW.LOAN_ID, loanId)
                .set(HARDSHIP_CASE_VIEW.MEMBER_REF, memberRef)
                .set(HARDSHIP_CASE_VIEW.LEVEL, level)
                .set(HARDSHIP_CASE_VIEW.ORIGIN, origin)
                .set(HARDSHIP_CASE_VIEW.OPENED_ON, openedOn)
                .onConflict(HARDSHIP_CASE_VIEW.CASE_ID).doUpdate()
                .set(HARDSHIP_CASE_VIEW.LEVEL, level)
                .set(HARDSHIP_CASE_VIEW.ORIGIN, origin)
                .set(HARDSHIP_CASE_VIEW.OPENED_ON, openedOn)
                .set(HARDSHIP_CASE_VIEW.UPDATED_AT, PgSql.now())
                .execute();
    }

    /** The applied marker can arrive before the opening event; the case is then OPEN until its own events say otherwise. */
    public void applied(UUID caseId, UUID loanId, UUID memberRef) {
        dsl.insertInto(HARDSHIP_CASE_VIEW)
                .set(HARDSHIP_CASE_VIEW.CASE_ID, caseId)
                .set(HARDSHIP_CASE_VIEW.LOAN_ID, loanId)
                .set(HARDSHIP_CASE_VIEW.MEMBER_REF, memberRef)
                .set(HARDSHIP_CASE_VIEW.APPLIED, true)
                .onConflict(HARDSHIP_CASE_VIEW.CASE_ID).doUpdate()
                .set(HARDSHIP_CASE_VIEW.APPLIED, true)
                .set(HARDSHIP_CASE_VIEW.UPDATED_AT, PgSql.now())
                .execute();
    }

    public void closed(UUID caseId, UUID loanId, UUID memberRef, LocalDate closedOn, String reason) {
        dsl.insertInto(HARDSHIP_CASE_VIEW)
                .set(HARDSHIP_CASE_VIEW.CASE_ID, caseId)
                .set(HARDSHIP_CASE_VIEW.LOAN_ID, loanId)
                .set(HARDSHIP_CASE_VIEW.MEMBER_REF, memberRef)
                .set(HARDSHIP_CASE_VIEW.STATE, "CLOSED")
                .set(HARDSHIP_CASE_VIEW.CLOSED_ON, closedOn)
                .set(HARDSHIP_CASE_VIEW.CLOSED_REASON, reason)
                .onConflict(HARDSHIP_CASE_VIEW.CASE_ID).doUpdate()
                .set(HARDSHIP_CASE_VIEW.STATE, "CLOSED")
                .set(HARDSHIP_CASE_VIEW.CLOSED_ON, closedOn)
                .set(HARDSHIP_CASE_VIEW.CLOSED_REASON, reason)
                .set(HARDSHIP_CASE_VIEW.UPDATED_AT, PgSql.now())
                .execute();
    }
}
