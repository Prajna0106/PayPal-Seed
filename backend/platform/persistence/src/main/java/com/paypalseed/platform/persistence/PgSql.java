package com.paypalseed.platform.persistence;

import java.time.Instant;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/** PostgreSQL expressions jOOQ has no typed counterpart for. */
public final class PgSql {

    private static final Field<Instant> NOW = DSL.field("now()", SQLDataType.INSTANT);

    private PgSql() {
    }

    /** The database transaction's clock: use it for {@code updated_at} and for "due by now" comparisons. */
    public static Field<Instant> now() {
        return NOW;
    }
}
