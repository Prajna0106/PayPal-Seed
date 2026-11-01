package com.paypalseed.deskquery.infrastructure.persistence;

import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.APPROVAL_VIEW;

import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Governance approval requests. stage: 0 waits for the maker, 1 for the checker, 2 is finished. Stage only moves forward and a
 * finished request never returns to OPEN, so the two events of a request can arrive in either order.
 */
@Repository
public class ApprovalViewRepository {

    public static final int WAITING_FOR_MAKER = 0;
    public static final int WAITING_FOR_CHECKER = 1;
    public static final int FINISHED = 2;

    private final DSLContext dsl;

    public ApprovalViewRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void requested(UUID requestId, UUID subjectId, String tier, Instant slaDueAt) {
        dsl.insertInto(APPROVAL_VIEW)
                .set(APPROVAL_VIEW.REQUEST_ID, requestId)
                .set(APPROVAL_VIEW.SUBJECT_ID, subjectId)
                .set(APPROVAL_VIEW.TIER, tier)
                .set(APPROVAL_VIEW.SLA_DUE_AT, slaDueAt)
                .onConflict(APPROVAL_VIEW.REQUEST_ID).doUpdate()
                .set(APPROVAL_VIEW.TIER, tier)
                .set(APPROVAL_VIEW.SLA_DUE_AT, slaDueAt)
                .set(APPROVAL_VIEW.UPDATED_AT, PgSql.now())
                .execute();
    }

    public void decided(UUID requestId, UUID subjectId, String requestStatus, int stage) {
        dsl.insertInto(APPROVAL_VIEW)
                .set(APPROVAL_VIEW.REQUEST_ID, requestId)
                .set(APPROVAL_VIEW.SUBJECT_ID, subjectId)
                .set(APPROVAL_VIEW.STATE, requestStatus)
                .set(APPROVAL_VIEW.STAGE, stage)
                .onConflict(APPROVAL_VIEW.REQUEST_ID).doUpdate()
                .set(APPROVAL_VIEW.STAGE, DSL.greatest(APPROVAL_VIEW.STAGE, DSL.val(stage)))
                .set(APPROVAL_VIEW.STATE, DSL.case_().when(APPROVAL_VIEW.STATE.ne("OPEN"), APPROVAL_VIEW.STATE)
                        .otherwise(requestStatus))
                .set(APPROVAL_VIEW.UPDATED_AT, PgSql.now())
                .execute();
    }
}
