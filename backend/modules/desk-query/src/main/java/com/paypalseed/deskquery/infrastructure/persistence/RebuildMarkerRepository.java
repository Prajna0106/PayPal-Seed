package com.paypalseed.deskquery.infrastructure.persistence;

import static com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.REBUILD_DONE;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Which one-time rebuilds have finished. */
@Repository
public class RebuildMarkerRepository {

    private final DSLContext dsl;

    public RebuildMarkerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public boolean isDone(String name) {
        return dsl.fetchExists(REBUILD_DONE, REBUILD_DONE.NAME.eq(name));
    }

    /** Idempotent: two instances that finish together leave one row. */
    public void markDone(String name) {
        dsl.insertInto(REBUILD_DONE).set(REBUILD_DONE.NAME, name).onConflictDoNothing().execute();
    }
}
