package com.paypalseed.ledger.api;

import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.PostingRequest;
import java.util.UUID;

/**
 * The only way to move money in the books (STRUCTURE.md §3.2: only ledger writes journal tables).
 * {@link #post} must run inside the caller's transaction, so a money state change and its journal
 * entry commit together or not at all (ADR 0002).
 */
public interface LedgerFacade {

    /**
     * Posts a balanced entry and returns its id. Posting the same source key again with the same
     * lines returns the existing id; reusing a source key for different lines is an invariant breach.
     */
    UUID post(PostingRequest request);

    /** Balance on the account's normal side (positive means the account holds value). */
    long balanceCents(AccountRef account);
}
