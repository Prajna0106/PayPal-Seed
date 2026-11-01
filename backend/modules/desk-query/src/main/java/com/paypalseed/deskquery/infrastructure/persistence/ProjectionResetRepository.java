package com.paypalseed.deskquery.infrastructure.persistence;

import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.APPLICATION_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.APPROVAL_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.ASSESSMENT_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.HARDSHIP_CASE_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.LOAN_INSTALMENT_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.LOAN_MISSED_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.LOAN_VIEW;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS;
import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS_MONTH;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection;
import com.paypalseed.platform.persistence.PgSql;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Empties one projection and repairs the columns other projections derive from it, so a reset never leaves a flag that
 * the replay would not set again.
 */
@Repository
public class ProjectionResetRepository {

    private final DSLContext dsl;

    public ProjectionResetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Returns the number of rows removed from the projection's own tables. */
    public long reset(Projection projection) {
        return switch (projection) {
            case APPLICATIONS -> dsl.deleteFrom(APPLICATION_VIEW).execute();
            case ASSESSMENTS -> {
                int rows = dsl.deleteFrom(ASSESSMENT_VIEW).execute();
                dsl.update(APPLICATION_VIEW).set(APPLICATION_VIEW.ASSESSMENT_OUTCOME, (String) null)
                        .set(APPLICATION_VIEW.UPDATED_AT, PgSql.now()).where(APPLICATION_VIEW.ASSESSMENT_OUTCOME.isNotNull()).execute();
                yield rows;
            }
            case LOANS -> {
                int rows = dsl.deleteFrom(LOAN_VIEW).execute();
                dsl.deleteFrom(LOAN_INSTALMENT_VIEW).execute();
                dsl.deleteFrom(LOAN_MISSED_VIEW).execute();
                yield rows;
            }
            case HARDSHIP -> {
                int rows = dsl.deleteFrom(HARDSHIP_CASE_VIEW).execute();
                dsl.update(LOAN_VIEW).set(LOAN_VIEW.HARDSHIP_OPEN, false).set(LOAN_VIEW.UPDATED_AT, PgSql.now())
                        .where(LOAN_VIEW.HARDSHIP_OPEN.isTrue()).execute();
                yield rows;
            }
            case APPROVALS -> dsl.deleteFrom(APPROVAL_VIEW).execute();
            case SAVINGS -> {
                int rows = dsl.deleteFrom(MEMBER_SAVINGS).execute();
                dsl.deleteFrom(MEMBER_SAVINGS_MONTH).execute();
                dsl.update(APPLICATION_VIEW).set(APPLICATION_VIEW.SAVING_STREAK_MONTHS, 0)
                        .set(APPLICATION_VIEW.UPDATED_AT, PgSql.now()).where(APPLICATION_VIEW.SAVING_STREAK_MONTHS.ne(0)).execute();
                yield rows;
            }
        };
    }
}
