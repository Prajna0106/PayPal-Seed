package com.paypalseed.deskquery;

import com.paypalseed.platform.persistence.ModuleMigrations;
import com.paypalseed.platform.testing.EmbeddedPostgresSupport;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** One real PostgreSQL with every module's migrations applied, shared by the repository tests of this module. */
public final class DbFixture {

    private static DSLContext dsl;

    private DbFixture() {
    }

    public static synchronized DSLContext dsl() {
        if (dsl == null) {
            DataSource dataSource = new DriverManagerDataSource(EmbeddedPostgresSupport.database("deskquery_repositories"));
            new ModuleMigrations(dataSource).migrate();
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        }
        return dsl;
    }

    /** Empties every desk projection so each test starts from nothing. */
    public static void clean() {
        dsl().execute("""
                truncate deskquery.loan_view, deskquery.loan_instalment_view, deskquery.loan_missed_view,
                         deskquery.hardship_case_view, deskquery.member_savings, deskquery.member_savings_month,
                         deskquery.approval_view, deskquery.application_view, deskquery.assessment_view
                """);
    }
}
