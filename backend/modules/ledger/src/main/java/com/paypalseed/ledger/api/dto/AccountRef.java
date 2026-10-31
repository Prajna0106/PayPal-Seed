package com.paypalseed.ledger.api.dto;

import java.util.Objects;
import java.util.UUID;

/** Names an account by type and owner; the ledger creates it on first use. */
public record AccountRef(AccountType type, UUID ownerId) {

    public AccountRef {
        Objects.requireNonNull(type, "type");
        boolean needsOwner = type.owner() != AccountType.Owner.PROGRAM;
        if (needsOwner != (ownerId != null)) {
            throw new IllegalArgumentException(type + (needsOwner ? " needs an owner" : " is program-level"));
        }
    }

    public static AccountRef program(AccountType type) {
        return new AccountRef(type, null);
    }

    public static AccountRef of(AccountType type, UUID ownerId) {
        return new AccountRef(type, ownerId);
    }
}
