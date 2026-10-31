package com.paypalseed.ledger.infrastructure.persistence;

import static com.paypalseed.ledger.infrastructure.persistence.jooq.Tables.ACCOUNT;
import static com.paypalseed.ledger.infrastructure.persistence.jooq.Tables.JOURNAL_ENTRY;
import static com.paypalseed.ledger.infrastructure.persistence.jooq.Tables.JOURNAL_LINE;

import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.Side;
import com.paypalseed.platform.kernel.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** No other module touches the {@code ledger} tables. */
@Repository
public class JournalRepository {

    public record ExistingEntry(UUID id, String requestHash) {
    }

    private final DSLContext dsl;

    public JournalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Get-or-create; safe under concurrency thanks to the (type, owner) unique constraint. */
    public UUID accountId(AccountRef ref) {
        Optional<UUID> existing = findAccountId(ref);
        if (existing.isPresent()) {
            return existing.get();
        }
        dsl.insertInto(ACCOUNT)
                .set(ACCOUNT.ID, Ids.newId())
                .set(ACCOUNT.TYPE, ref.type().name())
                .set(ACCOUNT.OWNER_ID, ref.ownerId())
                .set(ACCOUNT.NORMAL_SIDE, ref.type().normalSide().name())
                .onConflictOnConstraint(DSL.name("account_one_per_owner"))
                .doNothing()
                .execute();
        return findAccountId(ref).orElseThrow();
    }

    private Optional<UUID> findAccountId(AccountRef ref) {
        return dsl.select(ACCOUNT.ID)
                .from(ACCOUNT)
                .where(ACCOUNT.TYPE.eq(ref.type().name()))
                .and(ACCOUNT.OWNER_ID.isNotDistinctFrom(DSL.val(ref.ownerId(), ACCOUNT.OWNER_ID)))
                .fetchOptional(ACCOUNT.ID);
    }

    /** Inserts the entry header, or returns empty when the source key was already posted. */
    public Optional<UUID> insertEntry(String sourceKey, String requestHash, String description, LocalDate businessDate) {
        UUID id = Ids.newId();
        int inserted = dsl.insertInto(JOURNAL_ENTRY)
                .set(JOURNAL_ENTRY.ID, id)
                .set(JOURNAL_ENTRY.SOURCE_KEY, sourceKey)
                .set(JOURNAL_ENTRY.REQUEST_HASH, requestHash)
                .set(JOURNAL_ENTRY.DESCRIPTION, description)
                .set(JOURNAL_ENTRY.BUSINESS_DATE, businessDate)
                .onConflict(JOURNAL_ENTRY.SOURCE_KEY)
                .doNothing()
                .execute();
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    public ExistingEntry existingEntry(String sourceKey) {
        return dsl.select(JOURNAL_ENTRY.ID, JOURNAL_ENTRY.REQUEST_HASH)
                .from(JOURNAL_ENTRY)
                .where(JOURNAL_ENTRY.SOURCE_KEY.eq(sourceKey))
                .fetchSingle(r -> new ExistingEntry(r.get(JOURNAL_ENTRY.ID), r.get(JOURNAL_ENTRY.REQUEST_HASH)));
    }

    public void insertLine(UUID entryId, UUID accountId, Side side, long amountCents) {
        dsl.insertInto(JOURNAL_LINE)
                .set(JOURNAL_LINE.ENTRY_ID, entryId)
                .set(JOURNAL_LINE.ACCOUNT_ID, accountId)
                .set(JOURNAL_LINE.SIDE, side.name())
                .set(JOURNAL_LINE.AMOUNT_CENTS, amountCents)
                .execute();
    }

    /** Debits minus credits; zero for an account that does not exist yet. */
    public long debitMinusCredit(AccountRef ref) {
        BigDecimal balance = dsl.select(DSL.coalesce(
                        DSL.sum(DSL.when(JOURNAL_LINE.SIDE.eq("DEBIT"), JOURNAL_LINE.AMOUNT_CENTS)
                                .otherwise(JOURNAL_LINE.AMOUNT_CENTS.neg())),
                        BigDecimal.ZERO))
                .from(JOURNAL_LINE)
                .join(ACCOUNT).on(ACCOUNT.ID.eq(JOURNAL_LINE.ACCOUNT_ID))
                .where(ACCOUNT.TYPE.eq(ref.type().name()))
                .and(ACCOUNT.OWNER_ID.isNotDistinctFrom(DSL.val(ref.ownerId(), ACCOUNT.OWNER_ID)))
                .fetchSingle()
                .value1();
        return balance.longValueExact();
    }
}
