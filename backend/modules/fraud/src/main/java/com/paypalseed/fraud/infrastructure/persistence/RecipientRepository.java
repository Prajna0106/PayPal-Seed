package com.paypalseed.fraud.infrastructure.persistence;

import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.PAYOUT_RECIPIENT;

import com.paypalseed.fraud.infrastructure.persistence.DestinationRepository.Change;
import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * The hash of the recipient of each member's latest payout (ADR 0027). Callers hold the member's lock, so read and write do
 * not race. Works like {@link DestinationRepository}, one level finer: who was paid, not only which wallet.
 */
@Repository
public class RecipientRepository {

    private final DSLContext dsl;

    RecipientRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Change record(UUID memberId, String refHash, Instant observedAt) {
        Optional<String> known = dsl.select(PAYOUT_RECIPIENT.REF_HASH).from(PAYOUT_RECIPIENT)
                .where(PAYOUT_RECIPIENT.MEMBER_ID.eq(memberId)).fetchOptional(PAYOUT_RECIPIENT.REF_HASH);
        if (known.isEmpty()) {
            dsl.insertInto(PAYOUT_RECIPIENT)
                    .set(PAYOUT_RECIPIENT.MEMBER_ID, memberId)
                    .set(PAYOUT_RECIPIENT.REF_HASH, refHash)
                    .set(PAYOUT_RECIPIENT.OBSERVED_AT, observedAt)
                    .execute();
            return Change.FIRST;
        }
        if (known.get().equals(refHash)) {
            return Change.SAME;
        }
        dsl.update(PAYOUT_RECIPIENT)
                .set(PAYOUT_RECIPIENT.REF_HASH, refHash)
                .set(PAYOUT_RECIPIENT.OBSERVED_AT, observedAt)
                .set(PAYOUT_RECIPIENT.UPDATED_AT, PgSql.now())
                .where(PAYOUT_RECIPIENT.MEMBER_ID.eq(memberId))
                .execute();
        return Change.CHANGED;
    }
}
