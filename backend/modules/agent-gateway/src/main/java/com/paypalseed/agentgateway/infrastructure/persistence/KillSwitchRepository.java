package com.paypalseed.agentgateway.infrastructure.persistence;

import static com.paypalseed.agentgateway.infrastructure.persistence.jooq.Tables.KILL_SWITCH;
import static com.paypalseed.agentgateway.infrastructure.persistence.jooq.Tables.KILL_SWITCH_EVENT;
import static org.jooq.impl.DSL.excluded;

import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class KillSwitchRepository {

    public record Row(String scope, String mode, String reason, String trippedBy, String changedBy, Instant changedAt) {
    }

    private final DSLContext dsl;

    public KillSwitchRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<Row> find(Collection<String> scopes) {
        return dsl.selectFrom(KILL_SWITCH).where(KILL_SWITCH.SCOPE.in(scopes))
                .fetch(r -> new Row(r.getScope(), r.getMode(), r.getReason(), r.getTrippedBy(), r.getChangedBy(),
                        r.getChangedAt()));
    }

    /** Locks the scope's row (if any) so concurrent changes are serialised and the history stays exact. */
    public Optional<String> lockMode(String scope) {
        return dsl.select(KILL_SWITCH.MODE).from(KILL_SWITCH).where(KILL_SWITCH.SCOPE.eq(scope)).forUpdate()
                .fetchOptional(KILL_SWITCH.MODE);
    }

    public void upsert(String scope, String mode, String reason, String trippedBy, String changedBy) {
        dsl.insertInto(KILL_SWITCH)
                .set(KILL_SWITCH.SCOPE, scope)
                .set(KILL_SWITCH.MODE, mode)
                .set(KILL_SWITCH.REASON, reason)
                .set(KILL_SWITCH.TRIPPED_BY, trippedBy)
                .set(KILL_SWITCH.CHANGED_BY, changedBy)
                .onConflict(KILL_SWITCH.SCOPE)
                .doUpdate()
                .set(KILL_SWITCH.MODE, excluded(KILL_SWITCH.MODE))
                .set(KILL_SWITCH.REASON, excluded(KILL_SWITCH.REASON))
                .set(KILL_SWITCH.TRIPPED_BY, excluded(KILL_SWITCH.TRIPPED_BY))
                .set(KILL_SWITCH.CHANGED_BY, excluded(KILL_SWITCH.CHANGED_BY))
                .set(KILL_SWITCH.CHANGED_AT, PgSql.now())
                .execute();
    }

    public void appendEvent(String scope, String fromMode, String toMode, String reason, String trippedBy, String changedBy) {
        dsl.insertInto(KILL_SWITCH_EVENT)
                .set(KILL_SWITCH_EVENT.SCOPE, scope)
                .set(KILL_SWITCH_EVENT.FROM_MODE, fromMode)
                .set(KILL_SWITCH_EVENT.TO_MODE, toMode)
                .set(KILL_SWITCH_EVENT.REASON, reason)
                .set(KILL_SWITCH_EVENT.TRIPPED_BY, trippedBy)
                .set(KILL_SWITCH_EVENT.CHANGED_BY, changedBy)
                .execute();
    }
}
