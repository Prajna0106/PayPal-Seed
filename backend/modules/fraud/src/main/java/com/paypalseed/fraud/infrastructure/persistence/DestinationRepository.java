package com.paypalseed.fraud.infrastructure.persistence;

import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.PAYOUT_DESTINATION;

import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** The hash of each member's current payout destination. Callers hold the member's lock, so read and write do not race. */
@Repository
public class DestinationRepository {

    public enum Change {
        /** No destination was known: this one is the baseline, not a change. */
        FIRST,
        SAME,
        CHANGED
    }

    private final DSLContext dsl;

    DestinationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Change record(UUID memberId, String refHash, Instant observedAt) {
        Optional<String> known = dsl.select(PAYOUT_DESTINATION.REF_HASH).from(PAYOUT_DESTINATION)
                .where(PAYOUT_DESTINATION.MEMBER_ID.eq(memberId)).fetchOptional(PAYOUT_DESTINATION.REF_HASH);
        if (known.isEmpty()) {
            dsl.insertInto(PAYOUT_DESTINATION)
                    .set(PAYOUT_DESTINATION.MEMBER_ID, memberId)
                    .set(PAYOUT_DESTINATION.REF_HASH, refHash)
                    .set(PAYOUT_DESTINATION.OBSERVED_AT, observedAt)
                    .execute();
            return Change.FIRST;
        }
        if (known.get().equals(refHash)) {
            return Change.SAME;
        }
        dsl.update(PAYOUT_DESTINATION)
                .set(PAYOUT_DESTINATION.REF_HASH, refHash)
                .set(PAYOUT_DESTINATION.OBSERVED_AT, observedAt)
                .set(PAYOUT_DESTINATION.UPDATED_AT, PgSql.now())
                .where(PAYOUT_DESTINATION.MEMBER_ID.eq(memberId))
                .execute();
        return Change.CHANGED;
    }
}
