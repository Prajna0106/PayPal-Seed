package com.paypalseed.deskquery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

/** The seek condition is built from typed fields and bound values; no cursor text can become SQL. */
class KeysetSqlTest {

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
    private final Field<Instant> created = DSL.field(DSL.name("created_at"), SQLDataType.INSTANT);
    private final Field<String> status = DSL.field(DSL.name("status"), SQLDataType.VARCHAR);
    private final Field<UUID> id = DSL.field(DSL.name("id"), SQLDataType.UUID);
    private final UUID anId = UUID.fromString("0197f3a4-1b2c-7d3e-8f40-5a6b7c8d9e10");

    @Test
    void sameDirectionUsesOneRowValueComparison() {
        Condition c = KeysetPager.seek(List.of(created, id), List.of(false, false), List.of(Instant.parse("2026-11-01T00:00:00Z"), anId));

        String sql = dsl.render(c);

        assertThat(sql).contains("(\"created_at\", \"id\") > (").doesNotContain(" or ");
        assertThat(dsl.extractParams(c)).hasSize(2);
    }

    @Test
    void descendingFlipsTheComparison() {
        Condition c = KeysetPager.seek(List.of(created, id), List.of(true, true), List.of(Instant.parse("2026-11-01T00:00:00Z"), anId));

        assertThat(dsl.render(c)).contains("(\"created_at\", \"id\") < (");
    }

    @Test
    void mixedDirectionsExpandIntoPrefixes() {
        Condition c = KeysetPager.seek(List.of(status, created, id), List.of(true, false, false),
                List.of("SUBMITTED", Instant.parse("2026-11-01T00:00:00Z"), anId));

        String sql = dsl.render(c);

        // (status < s) or (status = s and created > t) or (status = s and created = t and id > i)
        assertThat(sql).contains("\"status\" <").contains("\"created_at\" >").contains("\"id\" >");
        assertThat(sql.split(" or ", -1)).hasSize(3);
        assertThat(dsl.extractParams(c)).hasSize(6);
    }

    @Test
    void cursorValuesAreBoundNeverPastedIntoTheSql() {
        String attack = "x'); drop table deskquery.loan_view; --";
        Condition c = KeysetPager.seek(List.of(status, id), List.of(false, false), List.of(attack, anId));

        assertThat(dsl.render(c)).doesNotContain("drop").doesNotContain(attack);
        assertThat(dsl.extractParams(c).values().stream().map(p -> (Object) p.getValue()).toList()).contains(attack);
        // Inlined for logging, the quote is doubled, so the literal cannot end early.
        assertThat(dsl.renderInlined(c)).contains("'x''); drop table deskquery.loan_view; --'");
    }
}
