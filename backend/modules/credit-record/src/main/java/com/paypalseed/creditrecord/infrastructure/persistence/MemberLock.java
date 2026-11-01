package com.paypalseed.creditrecord.infrastructure.persistence;

import java.util.UUID;
import org.jooq.DSLContext;

/** One transaction-scoped lock per member, shared by the fact store and the share store. */
final class MemberLock {

    private MemberLock() {
    }

    /** Blocks until no other transaction holds this member's lock; released at commit or rollback. */
    static void acquire(DSLContext dsl, UUID memberId) {
        dsl.fetch("select count(*) from (select pg_advisory_xact_lock(hashtextextended(?::text, 0))) l",
                "creditrecord:" + memberId);
    }
}
